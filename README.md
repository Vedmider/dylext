# Dylext

[![CI](https://github.com/Vedmider/dylext/actions/workflows/ci.yml/badge.svg)](https://github.com/Vedmider/dylext/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License: BSL 1.1](https://img.shields.io/badge/license-BSL--1.1-blue.svg)](LICENSE)

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

Dylext serves **only dynamic code context** — it does not do doc retrieval. Documentation is served by
**Sestacon**, and both sit behind the **gateway** (`:18070`), which is the single ingress: the AI calls
the gateway for code (MCP `/mcp` → Dylext) or docs (REST `/api/retrieve` → Sestacon). Neither provider
calls the other. Full routing and flow in `docs/ARCHITECTURE.md`.

## Tools (MCP, JSON-RPC over stdio or HTTP `POST /mcp`)

`list_repos` · `repo_overview` · `languages` · `outline` · `search_symbols` · `get_digest` · `expand` · `impact_of` · `recent_changes` ·
`working_changes` · `index` · `sync` · `verify_claims` · `ledger`

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
mvn -B clean verify     # 130 unit + 67 Testcontainers ITs
bash scripts/dev-local.sh e2e   # 61 developer-activity cases on real git repos (strict gate)
```

The e2e suite (`e2e/dev_activity.py`, plan in `docs/DEV-ACTIVITY-TEST-PLAN.md`) covers fresh
downloads, git lifecycle, branches, odd files, multi-module repos and agent sessions.

## Run locally

```bash
bash scripts/dev-local.sh up      # build image (mirror) + pgvector + app, wait for health
bash scripts/dev-local.sh test    # A/B token-savings harness (see docs/EVIDENCE.md)
bash scripts/dev-local.sh mcp      # run as a stdio MCP server for an MCP client
bash scripts/dev-local.sh down     # tear down + wipe data
```

Persistence always runs in Docker (pgvector). The app itself runs either as a container (compose)
or as a plain `java -jar` on the host — no Kubernetes required.

## Tester agent (Dylext + Sestacon QA)

`agents/` holds a Kiro agent that tests Dylext and Sestacon by using them first (hard rule), finds gaps,
bugs and uncovered cases, logs them in `agents/tester/FINDINGS.md`, and fixes them test-first locally
(never pushes). It can improve its own prompt and catalog.

```bash
bash agents/install-agent.sh                 # installs + validates ~/.kiro/agents/dylext-sestacon-tester.json
bash agents/tester/dylext.sh up              # Dylext on :18090 (own Postgres); agent calls it via agents/tester/dx
kiro-cli chat --agent dylext-sestacon-tester # then: "start"
```

Sestacon API probe: `python3 e2e/sestacon_probe.py` (needs Sestacon on :18080). Native MCP servers may be
disabled by Kiro enterprise policy; `dx` uses the HTTP `/mcp` endpoint instead.

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

## How the apps fit together

For the full explanation of how the **gateway** (single ingress/router), **Dylext** (dynamic code +
live git state), and **Sestacon** (static docs/RAG) compose into one AI context-retrieval system —
including the tool-routing decision table and end-to-end flow — see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).


## Enterprise setup & support

Dylext is free for non-production use. If you want to run it in production — or just want it
wired into your stack without the lift — we offer commercial engagements:

- **Production/commercial license** — unlock production and competitive use before the Change Date.
- **Integration & setup** — deploy into your infrastructure (on-prem, VPC, or air-gapped) and wire
  it to your repositories, CI, IDEs, and AI agents / MCP clients.
- **Custom development** — new language parsers, connectors, and features prioritized for your roadmap.
- **Support & SLAs** — priority support, security reviews, upgrade assistance, and guaranteed response times.
- **Team enablement** — onboarding your engineers onto token-efficient, grounded AI workflows.

📩 Talk to us: **andrii.lisovets@gmail.com** — tell us your repos, scale, and the AI agents you run,
and we'll scope an integration.

## License

Source-available under the [Business Source License 1.1](LICENSE) — © 2026 Andrii Lisovets.
You may read, build from source, modify, and make **non-production** use freely. Production use
requires a commercial license until the Change Date, when each version converts to Apache-2.0.

- Licensed Work: Dylext v0.2.0
- Additional Use Grant: None
- Change Date: 2029-11-18 (v0.2.0; each release sets its own, ~3 years out)
- Change License: Apache License, Version 2.0

See [`LICENSE`](LICENSE) for the full text.
