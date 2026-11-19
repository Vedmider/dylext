# Skill: analyze-before-ingest (repo purpose/intent meta, C′ half-1)

How an AI agent produces Dylext repo-meta. Pairs with the `set_summary` tool and the roadmap
(`INGESTION-ROADMAP.md`, workstream B). The rule: **analyze the repo first, then attach purpose/intent
notes to symbols** — never snapshot structure Dylext already serves live.

## Rule

Before you reason about a repo you have not annotated, run the procedure below once. Re-run it for the
changed scope whenever `get_digest` shows a symbol has no summary (its note auto-invalidated because the
code changed).

1. **Orient** — `repo_overview` (modules, folders), `languages`, to understand the repo's shape.
2. **Map the important symbols** — for each module, `outline` the key files; use `impact_of` to find
   the high-blast-radius symbols (the ones worth a note). Do **not** try to annotate everything.
3. **Author intent, not structure** — for each chosen symbol write ONE line of *why it exists / what
   responsibility it owns / how it influences others* — the thing the code and signature do **not**
   already say. Never restate the signature, folder path, or call graph (Dylext serves those live).
4. **Ground it** — run `verify_claims` on your notes against the repo so any symbol/API you cite is
   real before you store it.
5. **Attach** — `set_summary {symbolId, text}` per symbol. `get_digest` then surfaces the note. The
   note is stamped with the symbol's current content hash, so it auto-invalidates on change.

## What to capture — your judgement

There is **no fixed template.** You decide what is worth a note for this repo and what each note says —
the repo's shape (a library, a service, a CLI, a monorepo, infra…) dictates what matters. Favour the
purpose/intent of the symbols an AI would most need oriented on (entrypoints, public API, high-blast
symbols). The only constraints are the boundaries below.

## Boundaries

- **Dylext owns structure.** Folder tree, symbols, signatures, call graph, dependencies are live via
  `repo_overview`/`outline`/`impact_of` — never ingest or restate them. Notes add *why*, not *what/where*.
- **One line per symbol.** If it needs a paragraph, it is probably broader narrative that does not
  belong as a per-symbol note — tighten it to the single most useful intent, or leave it out.
- **Freshness is automatic.** A note is stamped with the symbol's content hash (its signature, doc and
  call edges — its *contract*), so it drops when that contract changes. Seeing a missing note on a hot
  symbol is your signal to re-author that one — no scheduler, no manual tracking.
