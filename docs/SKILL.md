# Skill: Dylext — exact, dynamic code context

Provider-agnostic guidance for an AI agent on using **Dylext**. Reformat the wrapper for your
assistant; the body is the skill.

## What this app is

Dylext is the **dynamic code-context provider**: exact code structure (symbol skeletons and spans) and
live git state (recent commits + uncommitted working-tree diff), **digest-first** — cheap skeletons on
search/outline, raw source spans only on demand. It never returns a whole file when a symbol span will
do; that's the token saving.

Dylext is reached **through the gateway** (`:18070/mcp`), not directly. It is one of two providers
behind the gateway — Dylext (this, dynamic code) and **Sestacon** (static docs/RAG, reached via the
gateway's `/api/*`). Dylext serves **only** code/git context; it does **not** call Sestacon or do
document retrieval. Single responsibility: ask Dylext for code, ask Sestacon (via the gateway) for docs.

Tools (JSON-RPC over the gateway `/mcp`; the `dx` client targets the gateway by default):
- `outline(repo, path)` — every symbol's one-line skeleton for a file.
- `search_symbols(repo, query, topK, pathPrefix?)` — exact/lexical symbol lookup by name;
  `pathPrefix` (e.g. `web/`) restricts it to one module/folder.
- `list_repos()` / `repo_overview(repo)` / `languages()` — what is indexed (root, branch, counts),
  a repo's languages + modules + top folders, and which languages can be parsed. Start here on an
  unfamiliar repo.
- `get_digest(symbolId)` → `expand(symbolId)` — skeleton, then that symbol's raw span only.
- `impact_of(symbolId)` — callers + covering tests (watch `ambiguousOverload` on same-arity overloads).
- `recent_changes(repo)` / `working_changes(repo)` — commits / **uncommitted** diff vs HEAD.
- `index(repo, root)` / `sync(repo, root)` — build / incrementally refresh. `root` must be an
  **absolute, existing directory**.
- `verify_claims(answer, repo)` — ground an answer's cited symbols against the index (VERIFIED/UNVERIFIED).
- `ledger()` / `savings()` — running token-savings totals for the code tools.

## When to USE it

- You know the identifier: "jump to `Foo`", "show `Bar.method`'s body" → `search_symbols` → `expand`.
- You want a file's shape cheaply → `outline`.
- Blast radius of a change → `impact_of` (callers + tests).
- "What am I changing right now?" → `working_changes` (uncommitted — this is Dylext's dynamic edge).
- Ground a claim's code references → `verify_claims`.
- Any code-navigation step where **minimal tokens** matter (digest before body, span not file).

## When NOT to use it

- Conceptual "what/why/convention" questions, or anything over prose/docs → ask **Sestacon** via the
  gateway's `/api/retrieve`. Dylext does not answer doc questions.
- Arbitrary text/regex over unindexed or non-source files, logs, config → grep/ripgrep (Dylext indexes
  code symbols only; it is not a free-text search).
- Repo not indexed yet / stale → run `index` or `sync` first.

## How to use well — replaces "grep then read whole files"

1. Progressive disclosure, always: `search_symbols`/`outline` → `get_digest` → `expand`. A digest
   (signatures) answers most structural questions without a body.
2. `expand` exactly one symbol, only once you know you need its body. Never re-expand what you've seen.
3. Have an exact name? `search_symbols` directly — don't spend a doc query.
4. Editing? `working_changes` before reading committed code.
5. Watch the `tokens` footer — it's your running savings ledger.

## Multi-module repos

- Index the **repo root** once: all modules are searchable and `impact_of` follows cross-module calls.
  Use `repo_overview` to see modules and `search_symbols {pathPrefix: "<module>/"}` to scope.
- Submodules / nested repos are not part of the parent index; index them separately.

## Working across several repos — what Dylext does and does not do

Dylext is **pull-based**: it never watches your files. State is read when a caller asks.

- **Uncommitted edits** → `working_changes(repo)` reads live git on every call, per repo; always current.
- **The symbol index is commit-granular**: reads re-sync when a repo's git HEAD moves, so a method you
  added but have not committed isn't searchable yet. Use `working_changes` for the uncommitted diff; to
  make uncommitted code searchable call `index {repo, root}` (incremental, file-hash based).
- **A repo Dylext has not seen** → `index {repo, root}` first, else tools answer
  `-32602 repo not indexed`.

---

## Session memory (AI task reflection)

A **session is the AI's working memory for a task** — not a per-repo log. It spans everything the agent
did for a task across repos and lets a *new* session resume work and review **why** past decisions were
made. Two layers, split so reloading stays cheap:

- `decisions[]` — durable "why" layer ({type: decision|finding|next, what, why, …}); batched at
  checkpoints; **never** auto-evicted (the log you reload next session).
- `facts[]` — cheap "what" layer (mechanical edit-facts); bounded ring buffer (newest kept).

Tools (isolated — they touch only session records): `session_start(task)`,
`session_fact(id, {repo?, file?, symbol?, what})`, `session_commit(id, entries[])`,
`session_list(...)` (headers only), `session_recall(id, {full?, sections?})`, `session_close(id)`,
`session_purge()`.

Workflow: `session_start` at task start (or `session_recall` to resume); `session_fact` mechanical
edits as you go; one `session_commit` with a small batch of decisions (each with a one-line `why`) at
real checkpoints — never per message; `session_commit` closing decisions + `session_close` at the end.

See `ARCHITECTURE.md` for how the gateway, Dylext, and Sestacon fit together, and `AI-USAGE.md` for the
terse routing guide.
