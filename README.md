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
`working_changes` · `index` · `sync` · `explain` · `recall` · `verify_claims` · `answer` · `ledger`

`answer` is the unified front door: it classifies the question (code vs doc intent), fans out to
the symbol index and/or Sestacon docs, and returns merged, evidence-backed citations (symbol
locations + doc sources). Each side degrades independently.

Every `tools/call` response carries a `tokens` footer (running ledger totals).

## Architecture

Hexagonal. Domain is transport/storage/git-agnostic.
- **in-ports:** Index, Retrieve, Impact, DynamicContext, Sync, Ledger.
- **out-ports:** DigestStore, SkeletonExtractor, Summarizer, VcsClient, Tokenizer, LedgerStore.
- **adapters:** MCP stdio (`adapter/in/mcp`), Postgres (`adapter/out/store`), JavaParser
  (`adapter/out/parser`), JGit (`adapter/out/vcs`), NoOp summarizer (`adapter/out/summarize`).

Static context is keyed by content hash (cache-friendly, incremental). Dynamic context is computed
on read from git, never cached stale. Summaries are opt-in (default NoOp = extractive-only).

## Languages — swappable parser Lego

Parsing is a port (`SkeletonExtractor`), so a language is a pluggable implementation, not core code.
Dylext owns every pipeline (index, store, git, verify, ledger); a parser only turns a file into
symbols. The core never names a language — routing is data-driven by `dylext.languages` (extension →
language) + `dylext.parsers.<language>=<url>`.

- **Java** — in-process (`JavaParser`), always on.
- **Python / Go** — out-of-process sidecars (`parsers/python`, `parsers/go`) behind
  `HttpSkeletonExtractor`, each parsing with its own native tool (`ast`, `go/parser`) and emitting
  one neutral symbol contract (see **[`parsers/CONTRACT.md`](parsers/CONTRACT.md)** — the single
  source of truth, with a shared conformance fixture each sidecar is tested against).

Each parser emits **full fidelity**, not just names: typed signatures (params + return types;
Python hints/defaults/decorators), structural kinds (Go struct fields, interface methods,
consts/vars; Python module vars + class attributes), module import/dependency surface, and
**qualified call targets** (`recv.method`, `pkg.Func`, `self.attr`). Qualified targets are
normalized to their simple name at edge time so cross-file call resolution works uniformly across
Java, Go and Python — making `impact_of` / blast-radius meaningful in every language.

**Adding a language = a new sidecar + one config line — zero Dylext code change.** Parsers are
optional: with none configured Dylext is Java-only; a parser that is down/unreachable degrades to a
MODULE-only skeleton for its files (the index never fails on a parser). Dylext owns symbol identity
(`repo:filePath:symbolPath`) and hashing, so sidecars stay dumb and interchangeable.

```bash
# host jar: enable parsers (sidecars running on the host)
DYLEXT_PARSERS_PYTHON=http://localhost:9091 DYLEXT_PARSERS_GO=http://localhost:9092 \
  bash scripts/dev-local.sh mcp
# compose: the python-parser + go-parser services are wired automatically
docker compose up -d
```

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

## Standalone vs composed

Dylext and Sestacon run **independently** or **together** — the dependency is one-way
(Dylext → Sestacon) and optional.

- **Standalone:** run Dylext alone; all code tools work. Only `explain`/`recall`/`answer`'s doc side
  degrades (empty) when no doc service is reachable — code evidence is still returned.
- **Composed:** start Sestacon (`bash scripts/dev-local.sh up` in that repo, :18080), then point
  Dylext at it with `RAG_URL`. The `answer` tool then blends code + docs, propagating the caller's
  `roles` into Sestacon's RBAC so permission-aware retrieval holds across the boundary.
  Container: `compose` already defaults `RAG_URL=http://host.docker.internal:18080`; host jar:
  `RAG_URL=http://localhost:18080 bash scripts/dev-local.sh mcp`.

Sestacon never depends on Dylext, so it is unaffected by whether Dylext is present.

## Token savings

See `docs/EVIDENCE.md`. Measured +97% on scattered-symbol tasks against this repo's own source
(WITH-MCP digests+span vs reading whole candidate files). Reproduce with `scripts/ab-harness.py`
(real tiktoken) or `HarnessServiceIT` (headless, CI).


## Performance (measured)

Indexed a large external Java repo (1259 files → **18k symbols in 58 s**, ~309 symbols/s), then
tool-call latency over stdio on the warm index: `expand` p50 **3.5 ms**, `get_digest` **4.3 ms**,
`search_symbols` **24 ms** (p95 29 ms). Single-digit-ms reads are the static-index payoff.

## How the three apps fit together

For the full explanation of how **Sestacon** (concepts/docs), **code-memory** (fuzzy code recall),
and **Dylext** (exact code + live git state) compose into one AI context-retrieval system —
including the tool-routing decision table and end-to-end flow — see
[`docs/ARCHITECTURE-THREE-APPS.md`](docs/ARCHITECTURE-THREE-APPS.md).
