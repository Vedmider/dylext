# Three apps for AI context retrieval — how they fit together

This document explains how **rag-app**, **code-memory**, and **context-mcp** compose into one
context-retrieval system for AI-assisted development, what each is strongest at, how an AI agent
should route a question across them, and how the data flows end to end.

The guiding principle: **find cheap and conceptual first, read exact bytes only when needed.**
Each app owns one layer of that; context-mcp is the single front door the AI talks to.

---

## 1. The three apps at a glance

| App | Owns | Retrieval unit | Strongest question | Embeddings |
|---|---|---|---|---|
| **rag-app** | Prose: docs, skills, agents, design notes, conventions | document chunk (text + source) | "what / why / which / explain" | real (MiniLM/ONNX or OpenAI) |
| **code-memory** | Codebase recall across many files/repos | ranked symbol (+ callers/tests) | "where is the X logic" (name unknown, fuzzy) | hash (lexical/structural) |
| **context-mcp** | Exact code structure + live git state | one skeleton → one source span | "jump to NAME / show its body / what did I just change" | none (lexical + AST) |

They are **complementary, not competing**:
- rag-app answers *concepts* (natural language over documentation).
- code-memory answers *fuzzy code recall* (hybrid vector+text + graph ranking across a repo).
- context-mcp answers *exact code location + uncommitted edits* with minimal tokens, and is MCP-native.

```
                      ┌─────────────────────────────────────────────┐
   AI agent  ──MCP──▶ │                 context-mcp                 │
  (one toolset)       │  native code tools + ledger                 │
                      │                                             │
                      │   explain(query) ───HTTP──▶ rag-app         │  concepts / docs
                      │   recall(query)  ───HTTP──▶ code-memory      │  fuzzy code recall
                      └─────────────────────────────────────────────┘
```

---

## 2. Why three, not one

Each central bet is different and would compromise the others if merged:

- **rag-app** optimizes semantic recall over *prose*. Real embeddings are essential there; a doc-RAG
  is the right home for conceptual/NL search.
- **code-memory** optimizes *recall across a codebase* — hybrid vector+full-text with RRF and
  PageRank/PPR graph ranking, multi-language chunking. Good for "I don't know the name, find the area."
- **context-mcp** optimizes *tokens per known-target lookup* and *live-edit awareness*: symbol-exact
  `expand` (never a whole file), `working_changes` (uncommitted diff vs HEAD), and a token-savings
  ledger — all as MCP tools so the AI calls them directly with no API-ordering problem.

Collapsing them would force one embedding model and one chunking strategy to serve prose, code recall,
and exact spans at once — which is exactly the compromise this split avoids.

---

## 3. The unified tool surface (what the AI sees)

The AI connects to **one MCP endpoint (context-mcp)**. It exposes native code tools plus two
delegating tools that reach the siblings at their strongest. Every `tools/call` carries a `tokens`
footer feeding the savings ledger.

Native (context-mcp):
- `outline(repo, path)` — every symbol's one-line skeleton for a file.
- `search_symbols(repo, query, topK)` — exact/lexical symbol lookup by name.
- `get_digest(symbolId)` — skeleton (+ summary if built) for one symbol.
- `expand(symbolId)` — raw source span for exactly that symbol (never the whole file).
- `impact_of(symbolId)` — callers + covering tests (blast radius).
- `recent_changes(repo, path?)` — recent commits.
- `working_changes(repo)` — **uncommitted** changes vs HEAD.
- `index(repo, root)` / `sync(repo, root)` — build / incrementally refresh the index.
- `ledger()` — running token-savings totals.

Federated (delegating):
- `explain(query, roles?, topK?)` → **rag-app** `/api/retrieve` (conceptual / doc answers).
- `recall(query, topK?)` → **code-memory** `/api/search` (fuzzy, graph-ranked code recall).

Both federated tools **degrade to an empty list** if the backing service is down — the AI then falls
back to context-mcp's local `search_symbols`, which still works offline for exact identifiers.

---

## 4. How an AI should route a question (decision table)

Pick the cheapest tool that can answer; descend to exact bytes only when needed.

| The question is… | Tool | Backing app |
|---|---|---|
| concept / "what / why / which / convention / explain" | `explain(query)` | rag-app |
| fuzzy code / "where is the X logic", name unknown | `recall(query)` | code-memory |
| known identifier / "jump to NAME", list a file's symbols | `search_symbols` / `outline` | context-mcp |
| read one symbol's body | `get_digest` → `expand` | context-mcp |
| blast radius / "what calls this, which tests cover it" | `impact_of(symbolId)` | context-mcp |
| live edits / "what am I changing right now" | `working_changes(repo)` | context-mcp |
| history / "what changed recently" | `recent_changes(repo)` | context-mcp |
| how many tokens am I saving | `ledger()` | context-mcp |

Heuristics:
- Have an exact identifier? Go straight to `search_symbols` — don't spend a `recall`/`explain`.
- NL intent, no symbol name yet? `recall` (code) or `explain` (docs) **first**; let the hit name a
  symbol; **then** `search_symbols` → `expand` for the exact bytes.
- Behaviour described in docs/conventions? `explain`, let it point you at the code, then descend.
- Actively editing? `working_changes()` before reading committed code.

Progressive disclosure, always: `search_symbols`/`outline` → `get_digest` → `expand`. A digest is
signatures only and answers most structural questions without a body.

---

## 5. End-to-end flow (the daily loop)

```
concept ─ explain ─────────▶ rag-app names a capability/symbol  ("rate limiting" → RateLimitInterceptor)
            │
fuzzy  ─ recall ───────────▶ code-memory ranks candidate symbols across the repo (NL ok)
            │
exact  ─ search_symbols ───▶ context-mcp → get_digest → expand  (minimal tokens, the real span)
            │
edit   ─ working_changes ──▶ context-mcp shows what you're touching right now
            │
impact ─ impact_of ────────▶ context-mcp callers + tests (what you might break)
```

Worked example — "fix a bug in rate limiting":
1. `explain("how does rate limiting work")` → a doc/skill names `RateLimitInterceptor`.
2. `search_symbols(repo, "RateLimitInterceptor")` → exact symbol id.
3. `get_digest(id)` → signature + any summary (often enough to plan the fix).
4. `expand(id)` → the one method body you'll edit.
5. `impact_of(id)` → callers + covering tests.
6. `working_changes(repo)` → confirm your edit, uncommitted, before moving on.

Steps 1–3 cost a few hundred tokens; the grep-and-read-whole-files alternative costs thousands —
that delta is what the `ledger` records.

---

## 6. Data flow & boundaries

- **Static context** (code structure) is indexed by path, keyed by content hash (incremental). In
  context-mcp it is Java skeletons; in code-memory it is multi-language symbols + a call graph.
- **Dynamic context** (git history + uncommitted working tree) is computed on read, never cached
  stale — this is why `working_changes` reflects edits that aren't committed yet.
- **Document context** (prose) lives only in rag-app, embedded with a real model; it is never
  duplicated into the code servers. `explain` fetches it over HTTP on demand.
- **Freshness:** `sync`/`index --force` refresh the code index; rag-app re-ingests documents. Each
  app owns its own store; context-mcp federates by calling the others, it does not copy their data.

Configuration (context-mcp): `contextmcp.federation.rag-url` (default :18080) and `cm-url`
(default :8080); env `RAG_URL` / `CM_URL`. Federation is optional — with the siblings absent,
context-mcp still serves all native code tools.

---

## 7. Deployment shape (local, single developer)

```
AI agent ──stdio MCP──▶ context-mcp (java -jar, contextmcp.mcp.stdio=true)
                               │  HTTP
                   ┌───────────┴────────────┐
             rag-app (:18080)        code-memory (:8080)
             + pgvector + redis      + pgvector
```

- Persistence always runs in Docker (pgvector per app). Apps run as containers or plain `java -jar`.
- No Kubernetes required for local use. Each app is independent; bring up only what a task needs
  (pure code work needs only context-mcp; add rag-app for docs, code-memory for fuzzy recall).

---

## 8. Honest limitations

- code-memory uses **hash embeddings** (lexical/structural), not semantic — strong for exact tokens,
  weak for pure NL intent. That's why NL goes to `explain` (rag-app), not `recall`.
- `explain`/`recall` are recorded in the ledger as token **costs**; the *saving* materializes when the
  AI then `expand`s precisely instead of reading whole files.
- rag-app's KB in dev is ephemeral (wiped on restart) — ingest before retrieving.
- Measured savings (see `EVIDENCE.md`) are largest on scattered-symbol lookup tasks, which is the
  common agent case; a task that genuinely needs a whole large file read saves little.

See also: `AI-USAGE.md` (the terse routing guide mirrored into tool descriptions),
`GAP-COMPARISON.md` (context-mcp vs code-memory design comparison), and `EVIDENCE.md`
(token-savings measurements).
