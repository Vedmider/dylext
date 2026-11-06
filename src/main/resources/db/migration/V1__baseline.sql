-- context-mcp baseline schema.
-- Static context: symbols + their extractive skeletons (+ optional abstractive summaries).
-- Dynamic context (git) is computed on read and is NOT persisted here.
-- Call-graph edges support impact_of; repo_state supports incremental sync; ledger records savings.

CREATE EXTENSION IF NOT EXISTS vector;

-- One row per code symbol (class, method, field, module). id = repo:path:symbolPath.
CREATE TABLE symbols (
    id            TEXT PRIMARY KEY,
    repo          TEXT        NOT NULL,
    file_path     TEXT        NOT NULL,
    language      TEXT        NOT NULL,
    kind          TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    symbol_path   TEXT        NOT NULL,
    signature     TEXT        NOT NULL DEFAULT '',
    doc_first_line TEXT       NOT NULL DEFAULT '',
    start_line    INT         NOT NULL,
    end_line      INT         NOT NULL,
    content_hash  TEXT        NOT NULL
);
CREATE INDEX symbols_repo_file_idx ON symbols (repo, file_path);
CREATE INDEX symbols_name_idx      ON symbols (name);

-- Extractive digest text per symbol (names + signatures + imports + call targets),
-- keyed by content_hash so an unchanged symbol never needs re-extraction.
CREATE TABLE skeletons (
    symbol_id     TEXT PRIMARY KEY REFERENCES symbols (id) ON DELETE CASCADE,
    content_hash  TEXT        NOT NULL,
    skeleton_text TEXT        NOT NULL,
    token_count   INT         NOT NULL DEFAULT 0
);

-- Optional abstractive summary, cached by content_hash; invalidated when the hash changes.
CREATE TABLE summaries (
    symbol_id     TEXT PRIMARY KEY REFERENCES symbols (id) ON DELETE CASCADE,
    content_hash  TEXT        NOT NULL,
    summary_text  TEXT        NOT NULL,
    build_tokens  INT         NOT NULL DEFAULT 0
);

-- Call-graph edges for impact_of. dst_id resolves lazily; dst_name holds the unresolved target.
CREATE TABLE edges (
    id       BIGSERIAL PRIMARY KEY,
    repo     TEXT NOT NULL,
    src_id   TEXT NOT NULL,
    dst_id   TEXT,
    dst_name TEXT NOT NULL,
    kind     TEXT NOT NULL
);
CREATE INDEX edges_src_idx ON edges (src_id);
CREATE INDEX edges_dst_idx ON edges (dst_id);

-- Per-repo incremental sync cursor (git HEAD last indexed) and root path.
CREATE TABLE repo_state (
    repo       TEXT PRIMARY KEY,
    root       TEXT        NOT NULL,
    git_cursor TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Token-savings ledger: one row per served tool call (served vs whole-file baseline).
CREATE TABLE ledger (
    id             BIGSERIAL PRIMARY KEY,
    task_id        TEXT        NOT NULL DEFAULT '',
    tool           TEXT        NOT NULL,
    tokens_served  INT         NOT NULL,
    baseline_tokens INT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
