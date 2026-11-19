# Implementation plan — Tier 0 (foundation) + Tier 1

> **Status (live).** Phase 1 shipped, but *differently* than drafted below: the Gateway became a
> **separate repo** (`dylext-gateway`, thin stateless reverse proxy) and Dylext↔Sestacon federation
> was **removed entirely** — so there is no in-process `ContextProvider` port and Dylext no longer
> composes multi-source answers. Consequence for Phase 2: epoch is **per-provider**, not a federated
> vector inside Dylext. **Phase 2 (epoch) is DONE** on the Dylext side: every repo-scoped `tools/call`
> result carries `result.epoch = {provider:"dylext", repo, commit=live git HEAD, dirty}`; `working_changes`
> is `dirty:true` (never cacheable); non-repo tools omit it. Flag `dylext.epoch.enabled` (default on).
> **Phase 3 (response cache) is DONE**: `ResponseCache` (bounded LRU, `dylext.cache.enabled`, default off)
> caches deterministic reads (outline/repo_overview/search_symbols/get_digest/expand/impact_of) keyed by
> (tool, args, epoch); the result carries `cached:true|false` and `savings` reports hit-rate. Correctness
> fix during build: the epoch is keyed on **live git HEAD** (not the stored index cursor) so a new commit
> always busts the key and the recompute path runs freshness-sync — a cursor-keyed cache would serve stale
> data after a commit because a cache hit skips the sync.
> **Phase 4b (capability tiers) DONE**: `CapabilityTier` T1/T2/T3; each parser advertises its tier
> (java=T3, python/go/ts sidecars=T2 via `/health`, generic fallback=T1); `languages` reports it;
> `impact_of` below T2 returns a `capabilityNote` (byte-identical for T2/T3); `dylext.fallback-parser`
> routes otherwise-unsupported source files to a generic tree-sitter sidecar at T1.
> **Phase 4c (runtime signals) DONE**: `ingest_signals(repo, [{symbolId, coverage, callFrequency}])`
> persists per-symbol signals (`symbol_signal` table); `impact_of` reorders callers hot-and-untested-first
> (`weight = callFrequency * (1 - coverage)`) and exposes `signalRanking`; absent signals = byte-identical.
> **4a (doc↔code staleness) deferred** — cross-system, conflicts with the Dylext↔Sestacon decoupling;
> it belongs in a gateway-orchestrated job, not in Dylext.

Scope: Tier 0 + Tier 1 from docs/ARCHITECTURE-ROADMAP.md. **Tier 2 (cross-repo contract edges) and
Tier 3 (structural write path, team memory, planner self-tuning, cross-encoder rerank) are dropped.**

Rollout = strangler pattern: in-process Gateway inside Dylext's current MCP front door (no new
deployable yet), everything flag-gated and additive, MCP tool surface never breaks.

Build order: **Phase 1 Gateway (first) -> Phase 2 Epoch -> Phase 3 Cache**, observability in parallel,
then Phase 4 Tier 1. The Gateway is first because every later phase (cache, telemetry, staleness,
language tiers, signals) plugs into the ContextProvider port it introduces.

## Guiding constraints (every phase)
- Test-first; full `mvn verify` (testcontainers) + live `dx` smoke before merge.
- Flag-gated (`dylext.gateway.enabled`, `.epoch.enabled`, `.cache.enabled`), default off -> on after ITs.
- Additive only: new tools / new response fields; existing agents keep working.
- Gateway may route/meter/cache/ground — never re-implement provider domain logic (enforced in review).

## Phase 1 — Gateway + ContextProvider port (FIRST; no behavior change)
The seam everything else plugs into. Introduced with zero output change.
- Define `ContextProvider` port + `CapabilityDescriptor` (concept, fuzzy, structure, span, impact, live, doc, signal).
- Wrap Dylext-core and the HTTP federation (today's FederatedRetriever) as ContextProvider adapters.
- McpDispatcher -> Gateway -> providers (strangler). Same tools, same responses.
- Telemetry skeleton: every call emits {provider, capability, repo, latency, outcome} via the ledger/Prometheus (extends I-008).
- Maps to: FederatedRetriever/FederatedContextService, McpDispatcher, LedgerStore, I-008 metrics.
- Acceptance: all current ITs green; MCP surface byte-identical; per-call provider/capability/latency in metrics.
- Risk: refactor regressions -> snapshot existing tool responses before/after and assert equality.

## Phase 2 — Epoch vector (prereq for cache + staleness)
- EpochRef{provider, repo, token}: Dylext = git HEAD sha per repo; Sestacon = monotonic collection/ingestion version; working-tree = `dirty`.
- Thread epoch through answers; compose a VECTOR on multi-source answers; expose near the tokens footer.
- Live/uncommitted excluded: working_changes tagged `epoch=dirty`, never cacheable.
- Maps to: DigestStore.repoCursor/saveRepoCursor, Sestacon ingestion, response envelope.
- Acceptance: epoch stable across reads, changes on HEAD move; working_changes=dirty; cross-source answer carries a vector.
- Risk: Sestacon collection-version is new -> start coarse (per-ingest counter), refine later.

## Phase 3 — Capability-specific cache (external tier) [depends on Phase 2]
- `Cache` out-port; external tier (reuse Sestacon-stack Redis or a pg table).
- Keying is PER-CAPABILITY, not one scheme:
  - exact/structural (get_digest, expand, outline) -> (symbolId, content-hash)  [big win]
  - graph (impact_of) -> (symbolId, graph-epoch)  [content-hash is WRONG: cross-file callers]
  - semantic (recall, explain, answer) -> (query-hash, collection-epoch)  [modest, churn-sensitive]
- Invalidation = epoch bump (graph/semantic) or hash change (structural). Cache is external -> gateway stays stateless.
- Metric: hit-rate per capability.
- Acceptance: repeat calls hit; symbol edit busts digest; cross-file caller busts that symbol's impact; corpus write busts semantic; hit-rate visible.
- Risk: stale-serve -> every entry carries its epoch, validated on read; structural validated by content-hash.

## Cross-cutting — Observability -> quality signal (parallel with Phases 1-3)
- Promote the Phase-1 telemetry into a quality stream: verify_claims pass-rate, which-provider-answered, cache hit-rate, accept/reject hook.
- Acceptance: metrics show grounding pass-rate + per-provider answer share. NO routing decision depends on it yet (self-tuning stays deferred).

## Phase 4 — Tier 1 (after Phase 3; 4b/4c can start right after Phase 1)

### 4a Narrow doc<->code staleness reconciler (best ROI) [needs epoch + verify_claims]
- Reuse CitationVerifierService server-side: background reconciler walks Sestacon doc chunks, extracts cited symbol refs, checks against Dylext index, writes provenance flag per chunk (VERIFIED / STALE:<symbol>).
- Symbol/signature ONLY — no behavioral contradiction.
- Incremental: keyed by (doc-epoch, code-epoch); recheck only on epoch change.
- Surface: stale_docs(repo) and/or staleness annotation on explain/retrieve hits.
- Acceptance: doc citing removed/renamed symbol flagged; live-symbol doc clean; re-runs only on epoch change.
- Risk: ref-extraction precision -> bound to verify_claims patterns; unrecognized = "not a claim", never false STALE.

### 4b Universal language via capability tiers [needs Phase 1 port only]
- Formalize sidecar contract: stdin JSON -> {symbols, edges, tier}, tier in {T1 structure, T2 +edges/impact, T3 +typed overload resolution}.
- Tree-sitter generic fallback -> T1 for any language. Provider advertises tier; languages/repo_overview report it honestly.
- Acceptance: tree-sitter language reaches outline/search/expand; tier reported; impact_of on T1 returns "capability unavailable", not error.
- Risk: scope creep -> hard stop at T1 for the fallback; T2/T3 only with a dedicated analyzer.

### 4c Runtime signal provider (optional; proves the port) [needs Phase 1 port only]
- `Signal` capability supplying precomputed per-symbol features (coverage, call-frequency), pushed at index epoch.
- impact_of rerank consumes features when present (weighted order + exposes weight); absent -> identical to today.
- One reference ingest adapter (a coverage/trace export) as proof.
- Acceptance: with signal, impact ordering reflects it + shows weight; without, byte-identical.
- Risk: format sprawl -> exactly one reference format; others are future adapters.

## Sequencing
```
Phase 1 (Gateway, FIRST) ──┬─▶ Phase 2 (epoch) ──▶ Phase 3 (cache) ──▶ 4a staleness
                           ├─▶ Observability/quality (parallel)
                           ├─▶ 4b language tiers (after Phase 1)
                           └─▶ 4c runtime signals (after Phase 1)
```
