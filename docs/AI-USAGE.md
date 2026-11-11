# AI usage guide — the local context toolset

You (the AI) have ONE MCP endpoint: **Dylext**. It serves code context directly and federates
to two siblings at their strongest:

- **Sestacon** — conceptual / documentation retrieval over prose (docs, skills, agents, conventions).
  Reached via the `explain` tool.
- **code-memory** — fuzzy, graph-ranked recall across a codebase (hybrid vector+text, PageRank).
  Reached via the `recall` tool.
- **Dylext** (local) — token-exact code structure, spans, call graph, and live git state.

Route each question to the cheapest tool that can answer it. Find with `explain`/`recall`/`search`;
read bytes only with `expand`.

## Decide by question type (cheapest first)

| The question is… | Tool | Backing app |
|---|---|---|
| concept / "what / why / which / convention / explain" | `explain(query)` | Sestacon |
| fuzzy code / "where is the X logic", name unknown | `recall(query)` | code-memory |
| known identifier / "jump to NAME", list a file's symbols | `search_symbols` / `outline` | Dylext |
| read one symbol's body | `get_digest` then `expand(symbolId)` | Dylext |
| blast radius / "what calls this, which tests cover it" | `impact_of(symbolId)` | Dylext |
| live edits / "what am I changing right now" | `working_changes()` | Dylext |
| history / "what changed recently" | `recent_changes()` | Dylext |
| how many tokens am I saving | `ledger()` | Dylext |
| is the code this answer cites real? | `verify_claims(answer, repo)` | Dylext |

## Ground answers before trusting them (anti-hallucination)

When an answer (from `explain`, an LLM, or a doc) names code symbols — `Type#method`,
`Class.method`, `someMethod()` — run it through `verify_claims(answer, repo)` before relying on it.
Each cited symbol comes back **VERIFIED** (it exists, with its real signature + file + line) or
**UNVERIFIED** (not found in the index — likely hallucinated or stale). The answer is never
rewritten, only annotated, so you decide what to trust. Use this especially for answers that will
become handover docs, ADRs, or runbooks.

## Progressive disclosure (always)

`search_symbols` / `outline` → `get_digest` → `expand`. A digest is signatures only; it answers most
structural questions without a body. Call `expand` on ONE symbol, only once you know you need its
body. `expand` returns exactly that symbol's span — never a whole file.

## Routing heuristics

- Have an exact identifier? Go straight to `search_symbols` — don't spend a `recall`/`explain`.
- Natural-language intent, no symbol name yet? `recall` (for code) or `explain` (for docs/behaviour)
  FIRST; let the hit name a symbol; THEN `search_symbols` → `expand` for the exact bytes.
- Behaviour described in docs/skills/conventions? `explain`, let it point you at the code, then
  descend into Dylext.
- Actively editing? `working_changes()` before reading committed code — it shows your uncommitted diff.

## Token discipline

- Prefer `get_digest` over `expand`. Prefer `expand` over reading a file yourself.
- Don't re-`expand` a symbol you've already seen this session.
- Watch the `tokens` footer on each response — it is your running savings ledger.

## Degrade gracefully

`explain` and `recall` return an empty list when Sestacon / code-memory is down. On empty, fall back
to Dylext's local `search_symbols` (lexical) — you can still locate exact identifiers offline.

## Worked example — "fix a bug in rate limiting"

1. `explain("how does rate limiting work")` → doc/skill names `RateLimitInterceptor`.
2. `search_symbols(repo, "RateLimitInterceptor")` → exact symbol id.
3. `get_digest(id)` → signature + any summary (often enough to plan the fix).
4. `expand(id)` → the one method body you'll edit.
5. `impact_of(id)` → callers + covering tests, so you know what you might break.
6. `working_changes(repo)` → confirm your edit, uncommitted, before moving on.

Steps 1–3 cost a few hundred tokens; the alternative (grep + read whole files) costs thousands.
