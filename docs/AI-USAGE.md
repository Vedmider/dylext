# AI usage guide — the local context toolset

You (the AI) talk to **one ingress: the gateway** (`:18070`). Behind it are two independent providers:

- **Dylext** — exact, *dynamic* code structure + live git state. Reached as **MCP tools** via the
  gateway's `/mcp` (through the `dx` client, which targets the gateway by default).
- **Sestacon** — *static* documentation/RAG over prose (docs, skills, conventions). Reached as
  **REST** via the gateway's `/api/*` (`/api/retrieve`, `/api/ingest`).

The gateway routes each request to the owning provider; **the providers never call each other**. Route
each question to the cheapest backend that can answer; read bytes only with `expand`.

## Decide by question type (cheapest first)

| The question is… | Call (via gateway :18070) | Backend |
|---|---|---|
| concept / "what / why / which / convention / explain" | `curl -s localhost:18070/api/retrieve -d '{"query":"…","topK":5}'` | Sestacon |
| known identifier / "jump to NAME", list a file's symbols | `dx search_symbols` / `dx outline` | Dylext |
| read one symbol's body | `dx get_digest` then `dx expand(symbolId)` | Dylext |
| blast radius / "what calls this, which tests cover it" | `dx impact_of(symbolId)` | Dylext |
| live edits / "what am I changing right now" | `dx working_changes()` | Dylext |
| history / "what changed recently" | `dx recent_changes()` | Dylext |
| is the code this answer cites real? | `dx verify_claims(answer, repo)` | Dylext |
| how many tokens am I saving | `dx ledger()` / `dx savings()` | Dylext |

## Ground answers before trusting them (anti-hallucination)

When an answer (from a doc, an LLM, or Sestacon) names code symbols — `Type#method`, `someMethod()` —
run it through `verify_claims(answer, repo)` before relying on it. Each cited symbol comes back
**VERIFIED** (exists, with its real signature + file + line) or **UNVERIFIED** (not in the index —
likely hallucinated or stale). The answer is annotated, never rewritten. Use it especially for anything
that becomes a handover doc, ADR, or runbook.

## Progressive disclosure (always)

`search_symbols` / `outline` → `get_digest` → `expand`. A digest is signatures only; it answers most
structural questions without a body. Call `expand` on ONE symbol, only once you know you need its
body. `expand` returns exactly that symbol's span — never a whole file.

## Routing heuristics

- Have an exact identifier? Go straight to `search_symbols` — don't spend a doc query.
- Natural-language intent, no symbol name yet? Ask Sestacon (`/api/retrieve`) for the concept FIRST;
  let the hit name a symbol; THEN `search_symbols` → `expand` for the exact bytes.
- Actively editing? `working_changes()` before reading committed code — it shows your uncommitted diff.

## Token discipline

- Prefer `get_digest` over `expand`. Prefer `expand` over reading a file yourself.
- Don't re-`expand` a symbol you've already seen this session.
- Watch the `tokens` footer on code-tool responses — it is your running savings ledger.

## Degrade gracefully

If `dx` exits 2 the gateway is down — start it with `bash agents/tester/gateway.sh up`. If Sestacon doc
retrieval fails or returns empty, fall back to Dylext's local `search_symbols` (you can still locate
exact identifiers offline).

## Worked example — "fix a bug in rate limiting"

1. `POST /api/retrieve {"query":"how does rate limiting work"}` → a doc names `RateLimitInterceptor`.
2. `dx search_symbols {repo, "RateLimitInterceptor"}` → exact symbol id.
3. `dx get_digest(id)` → signature + any summary (often enough to plan the fix).
4. `dx expand(id)` → the one method body you'll edit.
5. `dx impact_of(id)` → callers + covering tests, so you know what you might break.
6. `dx working_changes(repo)` → confirm your edit, uncommitted, before moving on.

Steps 1–3 cost a few hundred tokens; the alternative (grep + read whole files) costs thousands.
