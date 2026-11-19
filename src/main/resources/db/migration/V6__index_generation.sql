-- Generation of the extractor/resolver that built a repo's index; older generations are rebuilt on sync.
ALTER TABLE repo_state ADD COLUMN index_generation INT NOT NULL DEFAULT 0;
