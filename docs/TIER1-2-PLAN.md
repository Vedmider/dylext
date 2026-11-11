# Tiers 1–2 — Parser quality uplift (implementation plan)

Goal: raise parser output from skeleton-level to high-fidelity symbols + a resolvable call
graph, **behind one neutral contract**, so the Lego property and an agnostic core are preserved.
Quality is proven with before/after metrics, not asserted.

## Standing decision (do NOT revisit now)
Java parsing stays **native / in-process** (`JavaSkeletonExtractor`). It is the reference
implementation of the `SkeletonExtractor` port. Extracting Java into its own parser microservice
is deferred to the **heavy-production phase**, where Dylext core becomes a language-agnostic
orchestrator and every language (incl. Java) is a parser microservice behind the HTTP contract.
Until then: Go + Python are sidecars; Java is in-process; the port already proves all are swappable.

## Architectural invariant
All enrichment lives in the **parsers** (sidecars + the Java extractor) and is expressed through a
single **neutral symbol contract**. Dylext core gains **zero** language-specific logic. Richer
resolution = richer contract fields populated per-language, consumed uniformly by Dylext.

---

## Phase 0 — Formalize the contract (single source of truth)
Problem: the neutral contract is currently implicit and duplicated across the Go sidecar, the
Python sidecar, and `HttpSkeletonExtractor`'s JSON mapping. Enriching it will drift without a
canonical definition.

Deliverables:
- `parsers/CONTRACT.md` — the neutral symbol JSON schema, `SymbolKind` vocabulary, field
  semantics, per-language mapping notes, and worked examples.
- A shared **conformance fixture**: a tiny source file per language + its expected neutral symbols,
  that each sidecar's self-test asserts against. Guarantees all sidecars agree on the contract.

Enriched contract (all new fields OPTIONAL — non-breaking; adapter already uses `path(...).asText(default)`):
```
{
  kind,            // MODULE|CLASS|INTERFACE|ENUM|RECORD|METHOD|CONSTRUCTOR|FIELD (Dylext vocab)
  name,
  symbolPath,      // Parent#member / Parent.Nested — Dylext builds id = repo:filePath:symbolPath
  signature,       // full: params (name+type), returns, modifiers/decorators
  docFirstLine,
  startLine, endLine,
  callTargets,     // NEW semantics: QUALIFIED where known (recv.method, pkg.Func, self.m, import alias)
  fqName,          // NEW optional: fully-qualified name for cross-file resolution
  receiver,        // NEW optional: receiver/owner type (Go method recv, Python class, Java type)
  returnType,      // NEW optional
  modifiers,       // NEW optional: [] (exported/public/static/async/decorator names)
  imports          // NEW optional: MODULE symbol only — imported names/aliases (dependency surface)
}
```

## Phase 1 — Fidelity (Tier 1): richer signatures + more kinds + imports
Per parser, walk more of the already-parsed AST. No new heavy deps.

- **Go sidecar** (`parsers/go`):
  - Full signatures: param names + types + return types.
  - New kinds: struct FIELD, interface METHOD (method set), package const/var → FIELD,
    embedded types. `receiver`, `returnType`, `modifiers=[exported?]`, `fqName=pkg.Type#method`.
  - MODULE `imports` = import specs (path + alias).
- **Python sidecar** (`parsers/python`):
  - Full signatures: type hints + defaults + `*args/**kwargs`; `modifiers` = decorators + async.
  - New kinds: module-level assignments → FIELD (constants/vars), nested functions,
    class attributes (annotated/assigned in class body) → FIELD. `receiver`=enclosing class,
    `returnType` from annotation, `fqName=module.Class.method`.
  - MODULE `imports` = import / from-import names + aliases.
- **Java extractor** (`JavaSkeletonExtractor`, in-process): populate the SAME new fields
  (`fqName`, `receiver`, `returnType`, `modifiers`; imports already captured) so all three agree.

TDD: extend each sidecar self-test + `HttpSkeletonExtractorTest` for the new fields; Java unit test.

## Phase 2 — Resolvable call edges (Tier 2): islands → call graph
- Sidecars emit **qualified** `callTargets` (receiver/namespace/import-resolved *within the file*):
  e.g. Go `recv.Method`, `pkg.Func`; Python `self.method`, `alias.func`.
- Wire Go/Python edges into Dylext's existing `resolvePendingEdges` (the SQL name-resolution already
  used for Java) so a qualified call resolves to the defining symbol **across files**.
- Decision: reuse `callTargets` carrying qualified strings — **no DB/`Symbol` schema change** first.
  Only add a `qualifiedName` column if the measured resolution rate proves it necessary.
- Result: `impact_of` / blast-radius becomes meaningful for Go + Python, not just Java.

## Phase 3 — Quality gate (prove it)
A `~/e2e` metrics step, before vs after, on the real repos used in the polyglot e2e:
- **Call-edge resolution rate** = % of emitted `callTargets` linked to a real indexed symbol.
- **Signature completeness** = % of callable symbols whose signature includes params+returns.
- **Kind coverage** = distinct `SymbolKind`s emitted per repo.
Targets: Go = `eric-ran-rad-lcm-batchservice`; Python = `cloudran-test-rce`.
Report before/after deltas. "Quality guaranteed" = measured improvement, no regressions.

## Phase 4 — Verify + ship
- Sidecar self-tests (python unittest, go test) + `mvn -B clean verify` (unit + Testcontainers ITs) green.
- Re-run the polyglot MCP e2e (`~/e2e/dylext_polyglot.py`) Go then Python; confirm richer symbols,
  no regression; capture the metrics.
- Commit (`-c commit.gpgsign=false`) + push `main` (docs updated in Phase 5 may ride the same or a
  follow-up commit).

## Phase 5 — Documentation sync (update only where code changed behavior)
Principle: update docs that describe behavior the new code changes; leave accurate docs untouched.
After Phases 1–3, review and update as needed:
- `README.md` → **Languages — swappable parser Lego**: note enriched fidelity (full signatures,
  more kinds, imports) + resolvable cross-file call edges for Go/Python; link `parsers/CONTRACT.md`.
  Update **Tools** / **Architecture** only if tool output shape is described there.
- `parsers/CONTRACT.md` (created Phase 0): the canonical schema — must match final emitted fields.
- `parsers/python/README`, `parsers/go/README` (add short per-sidecar READMEs if absent): what each
  emits, how to run, how it maps to the contract.
- `docs/ARCHITECTURE-THREE-APPS.md`: if it characterizes Dylext as Java-only or skeleton-level,
  correct to polyglot + resolvable edges; restate the Java-native / future-microservice decision.
- `docs/SKILL.md` + `docs/AI-USAGE.md`: if they tell an AI *when* to use code tools or claim
  language scope, update to reflect Java+Go+Python and the richer `impact_of`.
- `AGENTS.md` / `CLAUDE.md`: update only if they state language scope or tool behavior.
- `docs/EVIDENCE.md`: add the Phase-3 before/after quality metrics (resolution rate, signature
  completeness, kind coverage) as measured evidence.
- Leave `GAP-*` docs as-is unless a claim is now false.
Checklist gate: grep the doc set for "Java-only", "skeleton", "approximate edges", single-language
phrasing; reconcile each hit with the shipped behavior.

## Sequencing & risk
0 → 1 → 2 → 3 → 4 → 5. Phase 0 first so 1–2 don't drift; Phase 5 last so docs match final code.
Each phase is independently shippable and green. Non-breaking throughout (optional fields,
default-tolerant adapter, no schema change in P2). Backout = revert a phase; contract additions
are additive; doc edits are reversible.

## Out of scope (recorded elsewhere)
- Tier 3 (go/types, Python binding resolution, CPG/dataflow): `docs/TIER3-PLAN.md`.
- Java → microservice: heavy-production phase only (see Standing decision above).
