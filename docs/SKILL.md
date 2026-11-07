# Skill: context-mcp — exact code + live state (and the MCP front door)

Provider-agnostic guidance for an AI agent on using **context-mcp**. Reformat the wrapper for your
assistant; the body is the skill.

## What this app is

context-mcp is the **single MCP endpoint** the agent talks to. It serves **exact code structure**
(symbol skeletons and spans) and **live git state** (recent commits + uncommitted working-tree diff),
**digest-first**: cheap skeletons on search/outline, raw source spans only on demand. It never returns
a whole file when a symbol span will do — that's the token saving. It also **federates** to the other
two apps so the agent needs only one surface.

Tools (JSON-RPC over stdio):
- `outline(repo, path)` — every symbol's one-line skeleton for a file.
- `search_symbols(repo, query, topK)` — exact/lexical symbol lookup by name.
- `get_digest(symbolId)` → `expand(symbolId)` — skeleton, then that symbol's raw span only.
- `impact_of(symbolId)` — callers + covering tests.
- `recent_changes(repo)` / `working_changes(repo)` — commits / **uncommitted** diff vs HEAD.
- `index(repo, root)` / `sync(repo, root)` — build / incrementally refresh.
- `ledger()` — running token-savings totals.
- Federated: `explain(query)` → rag-app (concepts/docs); `recall(query)` → code-memory (fuzzy code).

## When to USE it

- You know the identifier: "jump to `Foo`", "show `Bar.method`'s body" → `search_symbols` → `expand`.
- You want a file's shape cheaply → `outline`.
- Blast radius of a change → `impact_of` (callers + tests).
- "What am I changing right now?" → `working_changes` (uncommitted — the other apps can't see this).
- Any code-navigation step where **minimal tokens** matter (digest before body, span not file).
- As the **router**: use `explain`/`recall` to reach docs/fuzzy-recall without leaving this endpoint.

## When NOT to use it

- Fuzzy "where is the X logic", name unknown → `recall` (code-memory) first, then come back to expand.
- Conceptual "what/why/convention" question → `explain` (rag-app).
- Arbitrary text/regex, logs, config, unindexed or non-Java files → **grep/ripgrep** (context-mcp
  indexes Java symbols only; it is not a free-text search).
- "Find every textual occurrence" → grep guarantees exhaustiveness; symbol search returns resolved,
  fewer, ranked hits.
- Repo not indexed yet / stale → run `index` or `sync` first.

## How to use well — replaces "grep then read whole files"

1. Progressive disclosure, always: `search_symbols`/`outline` → `get_digest` → `expand`. A digest
   (signatures) answers most structural questions without a body.
2. `expand` exactly one symbol, only once you know you need its body. Never re-expand what you've seen.
3. Have an exact name? `search_symbols` directly — don't spend a `recall`/`explain`.
4. Editing? `working_changes` before reading committed code.
5. Watch the `tokens` footer — it's your running savings ledger.
6. Degrade gracefully: `explain`/`recall` return empty if a sibling is down — fall back to local
   `search_symbols`.

## Fits with the other two apps

concept/docs → `explain` (**rag-app**) · fuzzy "where is the code" → `recall` (**code-memory**) ·
exact code + live edits → native **context-mcp** tools. Full routing: `docs/AI-USAGE.md` and
`docs/ARCHITECTURE-THREE-APPS.md`.


---

## Session memory (AI task reflection)

A **session is the AI's working memory for a task** — not a per-repo log. It spans everything the
agent did for that task across repos, and lets a *new* session resume work and review **why** past
decisions were made.

Two layers, deliberately split so reloading a session stays cheap (keep the AI context window well
under ~10% full):

```
session:<id>
  task          goal
  status        active | done
  repos[]       repos touched (0..n)
  decisions[]   durable "why" layer — {type(decision|finding|next), what, why, repo?, file?, symbol?, at}
                batched at checkpoints; NEVER auto-evicted (this is the log you reload next session)
  facts[]       cheap "what" layer — {type=fact, what, repo?, file?, symbol?, at}
                streamed as you edit; bounded ring buffer (newest kept, oldest dropped)
  updatedAt
```

Why two layers: decisions are few, carry rationale, and must survive for later sessions, so they are
append-only. Facts are many, mechanical (derived from working_changes), and only the recent ones
matter, so they are bounded — they can never bloat the record.

Tools (isolated from code tools — they touch only session records):
- `session_start(task)` → new session id.
- `session_fact(id, {repo?, file?, symbol?, what})` → stream **one** cheap edit-fact. Mechanical, no
  why. Bounded.
- `session_commit(id, entries[{type(decision|finding|next), what, why}])` → **batch** durable
  decision-layer entries at a checkpoint. `type` defaults to `decision`. Never evicted. Returns a header.
- `session_list(repo? | status? | text?)` → **headers only** (id, task, status, repos, decisionCount,
  factCount) — "what was I working on". No bodies.
- `session_recall(id, {full?, sections?[decisions|facts]})` → default = all decisions + the newest
  facts only (bounded). `full:true` = everything; `sections` picks which layer(s) to return.
- `session_close(id)` → mark done.
- `session_purge()` → evict expired sessions (TTL sweep; also runs on a schedule).

Retrieval is by **exact id** (like a database record), so a session query returns *only* session
records — never code context, and code tools never return sessions.

### Workflow — capture discipline
1. At task start: `session_start(task)`; if resuming, `session_recall(id)` first (default read is
   bounded — open `full:true` only when you actually need old facts).
2. As you edit: `session_fact` the mechanical "what" (repo/file/symbol changed). Stream freely — it's
   cheap and bounded. **Never** commit decisions per human message.
3. At checkpoints (a real decision taken, a finding learned, a next-step identified): one
   `session_commit` with a small **batch** of decision-layer entries, each with a one-line `why`.
   Batched, not per-sentence — this is what keeps the record small and the why durable.
4. At task end: `session_commit` any closing decisions/next-steps, then `session_close(id)`.

### Cleanup
Sessions are durable and would grow unbounded, so the store evicts: closed sessions purged after a
grace period, idle active sessions after a TTL, with a max-rows back-pressure cap. Facts are
additionally capped per-session (ring buffer); decisions are never auto-evicted. Use `session_purge`
(or the scheduled sweep) — never hand-delete rows. No LLM summarization anywhere (provider-agnostic).

