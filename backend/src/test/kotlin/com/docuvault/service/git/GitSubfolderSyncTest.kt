package com.docuvault.service.git

import com.docuvault.domain.space.ChangeType
import com.docuvault.domain.space.Space
import com.docuvault.domain.user.User
import com.docuvault.service.SettingsService
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * A space that syncs one folder of a repository (a monorepo's `docs/`) sees,
 * writes and versions only that folder, with paths relative to it, while the
 * clone and the pushes still cover the whole repository.
 */
class GitSubfolderSyncTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var remote: Path
    private lateinit var gitService: GitService
    private lateinit var diffService: GitDiffService

    @BeforeEach
    fun setUp() {
        val settings = mock(SettingsService::class.java)
        `when`(settings.getGitlabToken()).thenReturn("token")
        gitService = GitService(tmp.resolve("repos").toString(), settings, "docuvault-bot@localhost")
        diffService = GitDiffService(gitService)

        remote = tmp.resolve("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote.toFile()).call().close()
        withUpstreamClone { clone ->
            write(clone, "README.md", "# Monorepo")
            write(clone, "backend/App.kt", "fun main() {}")
            write(clone, "docs/guide.md", "# Guide\n\nfirst")
            write(clone, "docs/api/endpoints.md", "# Endpoints")
            commitAll(clone, "Initial layout")
        }
    }

    private fun docsSpace() = Space(
        id = UUID.randomUUID(),
        name = "Docs",
        slug = "docs",
        gitlabUrl = remote.toUri().toString(),
        gitSubfolder = "docs",
        createdBy = mock(User::class.java)
    )

    /** Someone else working on the repository from their own clone. */
    private fun withUpstreamClone(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory(tmp, "upstream")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(dir.toFile()).call().use { git ->
            block(dir)
            // The first clone is of an empty repository and sits on JGit's default branch.
            git.push().setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }
    }

    private fun write(root: Path, relative: String, content: String) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun commitAll(root: Path, message: String) {
        Git.open(root.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            git.commit().setMessage(message).setAuthor("Upstream", "upstream@example.com").call()
        }
    }

    /** Paths the newest commit on the remote touched. */
    private fun remoteHeadPaths(): List<String> = Git.open(remote.toFile()).use { git ->
        val repo = git.repository
        val commit = repo.parseCommit(repo.resolve("refs/heads/main"))
        val parent = repo.parseCommit(commit.parents.single())
        val reader = repo.newObjectReader()
        git.diff()
            .setOldTree(CanonicalTreeParser().also { it.reset(reader, parent.tree) })
            .setNewTree(CanonicalTreeParser().also { it.reset(reader, commit.tree) })
            .call()
            .map { if (it.newPath == DiffEntry.DEV_NULL) it.oldPath else it.newPath }
            .sorted()
    }

    @Test
    fun `the space shows only the synced folder, with paths relative to it`() {
        val space = docsSpace()
        gitService.cloneRepository(space)

        val tree = gitService.getFileTree(space)
        assertEquals(listOf("api", "guide.md"), tree.map { it.name })
        assertEquals("api/endpoints.md", tree.first().children!!.single().path)
        assertEquals("# Guide\n\nfirst", gitService.readFile(space, "guide.md"))
        assertEquals(listOf("api/endpoints.md", "guide.md"), gitService.listFilesUnder(space, ""))
    }

    @Test
    fun `nothing outside the folder can be reached through the space`() {
        val space = docsSpace()
        gitService.cloneRepository(space)

        assertThrows<IllegalArgumentException> { gitService.readFile(space, "../README.md") }
        assertThrows<IllegalArgumentException> { gitService.writeFile(space, "../backend/App.kt", "hacked") }
        assertFalse(gitService.regularFileExists(space, "../backend/App.kt"))
        assertFalse(gitService.deleteDirectory(space, ""))
    }

    @Test
    fun `edits land inside the folder and the push carries nothing else`() {
        val space = docsSpace()
        gitService.cloneRepository(space)

        // A stray change outside the folder must never ride along in a space commit.
        write(gitService.getRepoPath(space.id!!), "backend/App.kt", "fun main() { println() }")

        assertTrue(gitService.writeFile(space, "new-page.md", "# New"))
        assertTrue(gitService.writeFile(space, "guide.md", "# Guide\n\nsecond"))
        assertEquals(listOf("guide.md", "new-page.md"), gitService.getUncommittedFiles(space))

        gitService.commitAndPush(space, "Docs update", "Alice", "alice@example.com")

        assertEquals(listOf("docs/guide.md", "docs/new-page.md"), remoteHeadPaths())
        assertEquals(emptyList<String>(), gitService.getUncommittedFiles(space))
    }

    @Test
    fun `history and versions use space paths`() {
        val space = docsSpace()
        gitService.cloneRepository(space)
        gitService.writeFile(space, "guide.md", "# Guide\n\nsecond")
        gitService.commitAndPush(space, "Second version", "Alice", "alice@example.com")

        val history = diffService.fileHistory(space, "guide.md")
        assertEquals(listOf("Second version", "Initial layout"), history.map { it.message })
        assertEquals("guide.md", history.first().path)
        assertEquals("# Guide\n\nfirst", diffService.fileAtCommit(space, history.last().sha, "guide.md"))
        assertTrue(diffService.fileDiffAtCommit(space, history.first().sha, "guide.md")!!.contains("+second"))

        assertEquals("Alice", diffService.fileMeta(space, "guide.md").lastEdited?.authorName)
        assertEquals("Upstream", diffService.createdMany(space, listOf("guide.md"))["guide.md"]?.authorName)
        assertEquals(
            setOf("api", "guide.md"),
            diffService.folderEntryVersions(space, "", setOf("api", "guide.md")).keys
        )
    }

    @Test
    fun `pulled changes are reported for the folder only`() {
        val space = docsSpace()
        gitService.cloneRepository(space)
        val before = diffService.currentHeadSha(space)!!

        withUpstreamClone { clone ->
            write(clone, "backend/App.kt", "fun main() { println(1) }")
            write(clone, "docs/guide.md", "# Guide\n\nupstream edit")
            Files.move(clone.resolve("README.md"), clone.resolve("docs/overview.md"))
            Files.move(clone.resolve("docs/api/endpoints.md"), clone.resolve("backend/endpoints.md"))
            commitAll(clone, "Reshuffle")
        }
        gitService.pullChanges(space)

        val changes = diffService.changesBetween(space, before, diffService.currentHeadSha(space)!!)
            .associate { it.filePath to it.changeType }
        assertEquals(
            mapOf(
                "guide.md" to ChangeType.MODIFIED,
                // Moved in from outside the folder: arrives as a new file.
                "overview.md" to ChangeType.ADDED,
                // Moved out of the folder: gone as far as the space can tell.
                "api/endpoints.md" to ChangeType.DELETED
            ),
            changes
        )
        assertEquals("# Guide\n\nupstream edit", gitService.readFile(space, "guide.md"))
    }

    @Test
    fun `a folder that does not exist yet is created by the first document`() {
        val space = docsSpace().copy(gitSubfolder = "handbook")
        gitService.cloneRepository(space)

        assertEquals(emptyList<FileNode>(), gitService.getFileTree(space))
        gitService.writeFile(space, "welcome.md", "# Welcome")
        gitService.commitAndPush(space, "Start the handbook", "Alice", "alice@example.com")

        assertEquals(listOf("handbook/welcome.md"), remoteHeadPaths())
    }

    @Test
    fun `space and repository paths map both ways`() {
        val space = docsSpace()
        assertEquals("docs/a/b.md", space.repoPathOf("a/b.md"))
        assertEquals("docs", space.repoPathOf(""))
        assertEquals("a/b.md", space.spacePathOf("docs/a/b.md"))
        assertEquals("", space.spacePathOf("docs"))
        assertNull(space.spacePathOf("docs-old/a.md"))
        assertNull(space.spacePathOf("README.md"))

        val whole = space.copy(gitSubfolder = null)
        assertEquals("a/b.md", whole.repoPathOf("a/b.md"))
        assertEquals("README.md", whole.spacePathOf("README.md"))
    }

    @Test
    fun `typed folders are normalized and unsafe ones refused`() {
        assertEquals("docs", Space.normalizeGitSubfolder(" /docs/ "))
        assertEquals("backend/docs", Space.normalizeGitSubfolder("backend\\docs"))
        assertEquals("backend/docs", Space.normalizeGitSubfolder("backend//docs"))
        assertNull(Space.normalizeGitSubfolder(""))
        assertNull(Space.normalizeGitSubfolder("/"))
        assertNull(Space.normalizeGitSubfolder(null))
        assertThrows<IllegalArgumentException> { Space.normalizeGitSubfolder("../etc") }
        assertThrows<IllegalArgumentException> { Space.normalizeGitSubfolder("docs/../..") }
        assertThrows<IllegalArgumentException> { Space.normalizeGitSubfolder(".git/hooks") }
    }
}
