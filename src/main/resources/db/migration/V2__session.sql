-- AI task-reflection sessions. Isolated from code context (symbols/skeletons/edges): its own table,
-- keyed by session id, retrieved by exact id or filtered list. List fields are JSONB working notes.

CREATE TABLE session (
    id           TEXT PRIMARY KEY,
    task         TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | DONE
    repos        JSONB       NOT NULL DEFAULT '[]',
    decisions    JSONB       NOT NULL DEFAULT '[]',
    changes      JSONB       NOT NULL DEFAULT '[]',
    findings     JSONB       NOT NULL DEFAULT '[]',
    open_threads JSONB       NOT NULL DEFAULT '[]',
    refs         JSONB       NOT NULL DEFAULT '[]',
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX session_status_idx  ON session (status);
CREATE INDEX session_updated_idx ON session (updated_at);
CREATE INDEX session_repos_idx   ON session USING GIN (repos);
