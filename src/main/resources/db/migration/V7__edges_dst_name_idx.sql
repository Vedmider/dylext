-- reopenEdgesTo filters by target name on every index run.
CREATE INDEX IF NOT EXISTS edges_repo_dst_name_idx ON edges (repo, dst_name);
