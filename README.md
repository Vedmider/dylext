# context-mcp

An AI code-context **MCP server**. It keeps a codebase's **static context** (compressed code
skeletons: names + signatures + call targets) and **dynamic context** (git history + uncommitted
working-tree changes) in Postgres, and serves them to an AI agent **digest-first**: cheap skeletons
on search/outline, raw source spans only on demand via `expand`. The goal is to cut the tokens an
agent spends rediscovering a codebase. A token-savings ledger and an A/B harness measure it.

Clean-room sibling of `code-memory`, built to compare designs.

## Tools (MCP, JSON-RPC over stdio)

`outline` · `search_symbols` · `get_digest` · `expand` · `impact_of` · `recent_changes` ·
`working_changes` · `index` · `sync` · `ledger`

Every `tools/call` response carries a `tokens` footer (running ledger totals).

## Architecture

Hexagonal. Domain is transport/storage/git-agnostic.
- **in-ports:** Index, Retrieve, Impact, DynamicContext, Sync, Ledger.
- **out-ports:** DigestStore, SkeletonExtractor, Summarizer, VcsClient, Tokenizer, LedgerStore.
- **adapters:** MCP stdio (`adapter/in/mcp`), Postgres (`adapter/out/store`), JavaParser
  (`adapter/out/parser`), JGit (`adapter/out/vcs`), NoOp summarizer (`adapter/out/summarize`).

Static context is keyed by content hash (cache-friendly, incremental). Dynamic context is computed
on read from git, never cached stale. Summaries are opt-in (default NoOp = extractive-only).

## Build & test

```bash
source env.sh
mvn -B clean verify     # 58 tests (unit + Testcontainers ITs)
```

## Run locally

```bash
bash scripts/dev-local.sh up      # build image (mirror) + pgvector + app, wait for health
bash scripts/dev-local.sh test    # A/B token-savings harness (see docs/EVIDENCE.md)
bash scripts/dev-local.sh mcp      # run as a stdio MCP server for an MCP client
bash scripts/dev-local.sh down     # tear down + wipe data
```

Persistence always runs in Docker (pgvector). The app itself runs either as a container (compose)
or as a plain `java -jar` on the host — no Kubernetes required.

## Token savings

See `docs/EVIDENCE.md`. Measured +97% on scattered-symbol tasks against this repo's own source
(WITH-MCP digests+span vs reading whole candidate files). Reproduce with `scripts/ab-harness.py`
(real tiktoken) or `HarnessServiceIT` (headless, CI).
