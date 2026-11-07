-- AI task-reflection sessions. Isolated from code context (symbols/skeletons/edges): own table,
-- keyed by session id. Two JSONB layers: `decisions` (durable, batched, carry the why — never
-- auto-evicted) and `facts` (cheap streaming edits — bounded ring buffer). Each is a list of
-- unified entries {type, what, why?, repo?, file?, symbol?, at}.

CREATE TABLE session (
    id         TEXT PRIMARY KEY,
    task       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | DONE
    repos      JSONB       NOT NULL DEFAULT '[]',
    decisions  JSONB       NOT NULL DEFAULT '[]',
    facts      JSONB       NOT NULL DEFAULT '[]',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX session_status_idx  ON session (status);
CREATE INDEX session_updated_idx ON session (updated_at);
CREATE INDEX session_repos_idx   ON session USING GIN (repos);
