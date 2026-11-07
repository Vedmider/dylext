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
