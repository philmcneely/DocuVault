-- A Git-backed space can sync a single folder of its repository (e.g. "docs")
-- instead of the whole tree. NULL means the repository root.
ALTER TABLE spaces ADD COLUMN git_subfolder VARCHAR(500);
