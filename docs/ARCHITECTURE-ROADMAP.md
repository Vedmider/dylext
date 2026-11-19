# Ecosystem architecture roadmap — extending & accelerating Dylext + Sestacon + code-memory

Architecture-level design exploration (no implementation). Context: three providers —
**Dylext** (exact code + live git), **Sestacon** (doc RAG), **code-memory** (fuzzy graph recall) —
with Dylext as the single MCP front door that federates, digest-first, plus a token-savings ledger,
`verify_claims` grounding, and session memory.

---

## The one structural move everything else hangs off: split "router" from "code provider"

Today Dylext plays two roles at once: it is the **code-context provider** *and* the **federation
front door / router** that reaches sideways into Sestacon and code-memory. That coupling is fine at
three apps; it becomes the chokepoint the moment you add a fourth signal source.

North-star topology — turn the sideways reach into a star:

```
                 ┌───────────────────────────────┐
   AI agent ───▶ │   Context Gateway (thin)       │
   (one MCP)     │  router · planner · ledger ·   │
                 │  grounding · cache · policy     │
                 └───┬──────┬──────┬──────┬────────┘
                     │      │      │      │
              Dylext-core  Sestacon  code-memory  (new providers…)
              exact code   doc RAG   fuzzy graph   runtime/contracts/…
```

- The **Gateway** owns cross-cutting concerns: routing/planning, parallel fan-out + graceful
  degradation, the savings ledger, `verify_claims` grounding, caching, and RBAC/tenancy.
- **Dylext-core** becomes *just another provider* (exact code + git), peer to Sestacon and code-memory.
- Payoff: adding a provider never touches the router; the router's policy lives in one place; each
  provider scales and fails independently. Hexagonal architecture applied at the *system* level, not
  just inside each app.

The enabler is a single **ContextProvider port** — a capability contract every provider advertises:
`concept`, `fuzzy-recall`, `structure`, `span`, `impact`, `live-state`, `doc`, `write-op`, `signal`.
The planner routes by *capability + cost*, not by hard-wired app names. Everything below plugs into
this one seam.

## Make the consistency model explicit (prerequisite for caching, cross-repo, reproducibility)

Right now there are two clocks: the commit-granular symbol index and live `working_changes`. That is
a correct design, but it is implicit. Promote it to a first-class **epoch/snapshot** concept:

- Every answer is tagged with the `(repo, commit-or-epoch)` it was computed against.
- Gives read-your-writes semantics, reproducible answers, and — crucially — a cache key.
- The agent (and the ledger) can then reason about *freshness* instead of guessing.

## Content-addressed caching — the biggest "quicker" lever

Summaries are already cached by content-hash. Generalize it into a Gateway cache tier keyed by
`(provider, capability, content-hash, query-hash, epoch)`:

- Unchanged symbol → zero re-parse, zero re-embed, zero re-resolve.
- Embeddings are Sestacon's cost center; gate re-embedding strictly by chunk content-hash so only
  genuinely changed prose pays.
- Edge resolution (the type-aware resolver) is per-edge work today — design it as set-based/batch,
  with resolved edges cached by `(name, arity, paramTypes, repo-epoch)` and invalidated only for
  changed files.

This single idea turns incremental indexing from "re-do the file" into "re-do the handful of symbols
whose hash moved."

## Extension vectors (new capability, same seam)

1. **Cross-repo / architecture-wide `impact_of`.** Resolve call edges *across* services by treating
   contract artifacts (OpenAPI/proto/GraphQL) as first-class edges: client call → server handler.
   Needs a global symbol namespace and a "contract resolver" adapter. Turns blast-radius from
   intra-repo into system-wide — the highest-value extension for teams running microservices.

2. **Close the doc↔code loop (staleness/contradiction detection).** `verify_claims` today grounds an
   *answer's* code refs. Run it the other direction as a reconciliation job: check Sestacon's doc
   chunks against the Dylext index and flag docs that cite symbols that no longer exist or changed
   signature. A quality feature only this system can offer because it owns both the doc index and the
   exact code index. Design: a provenance/staleness signal on each doc chunk + a background reconciler.

3. **Structural write path.** Expose AST-level operations (rename, move, extract, change-signature) as
   provider capabilities that return *verified diffs* (run `impact_of` before/after). Agents edit via
   semantic ops instead of regenerating text — fewer tokens, safer edits, and the ledger can measure
   *edit*-token savings too, not just read savings.

4. **Runtime signal providers.** Coverage, traces, prod call-frequency as a pluggable `signal`
   capability that *weights* `impact_of` ranking: "these callers are hot and untested." Impact stops
   being purely structural and becomes risk-weighted.

5. **Universal language onboarding.** Formalize the parser-sidecar as a uniform
   `stdin-JSON → {symbols, edges}` contract with a tree-sitter-based generic fallback, so a new
   language is a config + adapter, not a core change.

6. **Team memory as a provider.** Promote session memory (the durable "why" decision layer) into a
   queryable provider the planner can consult — prior decisions become retrievable context, and the
   planner can avoid re-deriving what a past session already settled.

## Performance / quality vectors (design-level)

- **Cost-based query planner.** The `answer` front door should fan out cheap probes in parallel
  (concept + lexical), early-cancel the losers, and escalate to `expand` only on demand. Feed the
  existing ledger back as a signal — which tool actually answered, and did it verify — so routing
  self-tunes over time.
- **Two-stage retrieval in Sestacon.** Cheap ANN recall → optional cross-encoder rerank (reranker
  already exists), with rerank results cached. Tune HNSW, consider int8 quantization and dimension
  reduction; split hot/cold indexes.
- **Warm pools, stateless gateway.** Parser sidecars kept warm (no cold starts); Gateway stateless and
  horizontally scalable; pgvector as the heavy stateful tier with read replicas / per-repo sharding as
  it grows.
- **Streaming + pagination** for large `impact_of`/search results so the agent can act on first results
  without waiting for the tail.
- **Observability as a subsystem.** The ledger is proto-telemetry; elevate it to context-quality
  metrics: grounding rate, hallucination-catch rate, cache-hit rate, routing accuracy, staleness
  incidents. This is what makes the self-tuning planner possible and gives enterprise buyers a dashboard.

## Anti-patterns to steer around

- Don't let the Gateway re-implement provider logic — keep providers authoritative; the Gateway only
  routes, caches, grounds, meters.
- Don't merge code-memory's semantic index *into* Dylext's exact index. The exact-vs-fuzzy separation
  is a feature; unify them at the **planner**, not at storage.
- Keep sync **pull + content-hash** as the source of truth; add file-watching only as a cache-warming
  *hint*, never as the correctness mechanism.
- Don't over-embed — embeddings are the dominant cost; they must be strictly content-hash gated and
  on-demand.

## If I had to sequence the bets

1. Extract the Gateway + define the ContextProvider port (unlocks everything).
2. Epoch/snapshot consistency + content-addressed cache (the main speed win).
3. Doc↔code contradiction detection (unique quality differentiator, reuses `verify_claims`).
4. Cross-repo contract edges (biggest capability jump for real teams).
5. Structural write path (turns it from a *read* optimizer into a full agent substrate).
