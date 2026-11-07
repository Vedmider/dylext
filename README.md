# Dylext

An AI code-context **MCP server**. It keeps a codebase's **static context** (compressed code
skeletons: names + signatures + call targets) and **dynamic context** (git history + uncommitted
working-tree changes) in Postgres, and serves them to an AI agent **digest-first**: cheap skeletons
on search/outline, raw source spans only on demand via `expand`. The goal is to cut the tokens an
agent spends rediscovering a codebase. A token-savings ledger and an A/B harness measure it.

## What it's for / what questions it answers

Dylext is the **exact-code + live-state layer**, and the **single MCP endpoint** the AI talks to
(it also federates to the other two). It answers precise, token-cheap questions about code you can
name, plus what you're editing *right now* — returning one symbol's skeleton or span, never a whole
file.

Use it to answer:
- "Jump to `effectiveTopK` / show me `RetrieveController`." → exact symbol, signature, or span.
- "Give me the outline of this file." → every symbol's one-line skeleton.
- "Show the body of just this method." → `expand` returns that span only.
- "What calls this symbol, and which tests cover it?" → `impact_of` (blast radius).
- "What am I changing right now?" → `working_changes` (uncommitted diff vs HEAD).
- "What changed recently?" → `recent_changes` (commits).
- "How many tokens am I saving?" → `ledger`.

It also exposes two **federated** tools so the AI needs only one endpoint:
- `explain(query)` → Sestacon (concept/doc answers).
- `recall(query)` → code-memory (fuzzy code recall).

So across the three: **concept → `explain` (Sestacon); fuzzy where → `recall` (code-memory); exact
where/show + live edits → native Dylext tools.** Full routing and flow in
`docs/ARCHITECTURE-THREE-APPS.md`.

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


## How the three apps fit together

For the full explanation of how **Sestacon** (concepts/docs), **code-memory** (fuzzy code recall),
and **Dylext** (exact code + live git state) compose into one AI context-retrieval system —
including the tool-routing decision table and end-to-end flow — see
[`docs/ARCHITECTURE-THREE-APPS.md`](docs/ARCHITECTURE-THREE-APPS.md).
