# Tier 3 — Deep semantic resolution (deferred plan)

Tiers 1–2 (richer signatures/kinds, qualified + cross-file-resolvable call edges) are the
immediate work and give the bulk of the quality gain with no heavy dependencies. Tier 3 is
recorded here for when deep reverse-engineering becomes the goal; it is **not** scheduled.

## Scope

Move from *approximate, name-based* code understanding to *type-aware, dataflow-level* understanding.

### 3a. Go — `go/types` + `go/packages` (type-aware)
- Replace/augment the `go/ast`-only sidecar with `golang.org/x/tools/go/packages` loading in
  `NeedTypes | NeedTypesInfo | NeedSyntax` mode.
- Gives: resolved cross-package call targets, real declared types, interface method-set
  satisfaction, embedded-type promotion, constant values.
- Constraint: the target module must be **buildable/resolvable** (valid `go.mod`, deps fetchable).
  Degrade to the `go/ast` skeleton when load fails — never block indexing.
- Cost: slower (type-checks the module), needs the Go toolchain + module graph at index time.

### 3b. Python — scope/binding resolution
- Static binding resolution (which definition a name refers to: local / import / attribute) via a
  `jedi`- or `pyright`-style engine, or a hand-rolled scope walker for the common cases.
- Gives: import-resolved call edges, attribute→class-member linking, basic type inference from
  annotations.
- Constraint: Python's dynamism caps precision; treat results as best-effort with confidence.
- Cost: `jedi` is a heavy dep; `pyright` is a separate Node process (another sidecar runtime).

### 3c. Cross-language code property graph (CPG) / dataflow
- Unify resolved symbols + edges into a queryable CPG (def-use, data/control flow) for whole-repo
  reasoning (taint, reachability, architectural reconstruction).
- Only justified if the product's core job becomes reverse-engineering / security analysis.
- Cost: very high; effectively a new subsystem.

## Why deferred
- Tiers 1–2 already turn per-file islands into a resolvable call graph (via the enriched contract
  + Dylext's existing `resolvePendingEdges`), which covers most "blast radius" and navigation needs.
- Tier 3 trades large effort + heavy deps + build/runtime prerequisites for marginal precision that
  only a reverse-engineering-centric product needs.
- The LLM consumer tolerates approximate edges; fast/incremental indexing is the priority.

## Trigger to revisit
Build Tier 3 only if a concrete requirement appears that Tiers 1–2 cannot serve, e.g.:
- a verification gate that must *reject* answers on unresolved references, or
- whole-repo architecture reconstruction / dataflow/taint analysis as a product feature.

## Architectural invariant (applies to all tiers)
Everything stays **behind the neutral symbol contract** (`parsers/CONTRACT.md`). Type-aware engines
live in the per-language sidecars; Dylext core gains no language-specific code. Richer resolution =
richer contract fields populated by the sidecar, consumed uniformly by Dylext.
