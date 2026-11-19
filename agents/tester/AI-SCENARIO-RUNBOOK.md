# Dylext AI scenario runbook — added functionality (epoch, cache, tiers, signals)

A walk-through an AI agent executes **by hand** against the running apps to cover the functionality
added in Phases 2–4 and to catch gaps / bugs / inconsistencies. Every scenario is self-contained:
goal, fixture, exact calls, expected result, and what to flag. The automated mirror of each scenario
is noted as `auto: <id>` (in `e2e/dev_activity.py`); this file is the manual/AI counterpart.

> HARD RULE: talk to the apps **only through the gateway** (`http://localhost:18070`). `dx` already
> defaults to it. Never hit Dylext :18090 or Sestacon :18080 directly.

---

## 0. Conventions (read once)

Bring the stack up:
```bash
bash agents/tester/gateway.sh up && bash agents/tester/dylext.sh up && bash agents/tester/parsers.sh up
```
After rebuilding Dylext (`mvn … package`), `dylext.sh up` now detects a jar newer than the running
server and restarts automatically — so you never test a stale binary. (`up` still reuses a healthy
server when the jar is unchanged; `restart` always restarts.)

Two ways to call, and when to use each:

- **Payload fields** (`callers`, `signalRanking`, `capabilityNote`, symbol lists, `accepted`, tiers):
  use the CLI, which unwraps to the tool payload:
  ```bash
  agents/tester/dx <tool> '<json-args>'
  ```
- **Envelope fields** (`epoch`, `cached`, `tokens`): `dx` strips these, so read the raw JSON-RPC
  result from the gateway:
  ```bash
  curl -s -XPOST http://localhost:18070/mcp -H 'content-type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"<tool>","arguments":<json-args>}}' \
    | python3 -c 'import sys,json; r=json.load(sys.stdin)["result"]; print("epoch=",r.get("epoch")," cached=",r.get("cached"))'
  ```

Fixture helper (a tiny real git repo):
```bash
mkrepo(){ d=/tmp/tester-$1; rm -rf "$d"; mkdir -p "$d"; (cd "$d" && git init -q && \
  git config user.email t@t && git config user.name t); echo "$d"; }
```

Classify each result: **PASS** (expected), **GAP** (desired behaviour missing), **BUG** (wrong
behaviour), or **INCONSISTENCY** (two tools disagree). Record non-PASS in `agents/tester/FINDINGS.md`.

### Test-design rules (why this runbook catches what the headless harness missed)
The automated `.py` suite ran each tool ONCE under the DEFAULT config (cache OFF) and rarely re-read the
SAME tool after a state change — so it missed F-017 (signals×cache) and F-018 (repo_overview freshness).
When adding or running scenarios, always:
1. **Exercise cacheable reads with the cache ON** (`--dylext.cache.enabled=true`). Cacheable reads:
   outline, repo_overview, search_symbols, get_digest, expand, impact_of, recent_changes.
2. **Re-read the same tool after a change** that should affect it — a commit (epoch bust) AND a non-git
   mutation (`ingest_signals`). Assert the re-read is `cached:false` AND returns the NEW content (not a
   stale hit). A tool that is the *only* reader must still be fresh (nothing else syncs the index for it).
3. **Probe feature interactions**, not just each feature alone: cache×freshness, cache×signals,
   epoch×cache, tier×impact.

---

## 1. Epoch (Phase 2) — every repo-scoped answer is tagged with the commit it was computed against

### EP01 — a committed read carries a committed epoch  `auto: EP01`
- Fixture: `d=$(mkrepo ep01)`; write `A.java` (`package p; public class A { void m(){} }`), commit; index.
  ```bash
  agents/tester/dx index '{"repo":"ep01","root":"'"$d"'"}'
  ```
- Step: read the envelope of `recent_changes`:
  ```bash
  curl ... name=recent_changes arguments={"repo":"ep01"}
  ```
- Expect: `epoch = {provider:"dylext", repo:"ep01", commit:<40-hex>, dirty:false}`.
- Flag if: epoch missing, commit empty, or `dirty:true` on a committed read.

### EP02 — `working_changes` is `dirty` (never cacheable)  `auto: EP02`
- Step: envelope of `working_changes {repo:"ep01"}`.
- Expect: `epoch.dirty == true`, same `commit` as EP01.
- Flag if: `dirty:false` (a live/working-tree read must never be cacheable).

### EP03 — symbolId tools derive the epoch from the id prefix; non-repo tools omit it  `auto: EP03`
- Steps: envelope of `impact_of {symbolId:"ep01:A.java:p.A#m()"}` and of `ledger {}`.
- Expect: `impact_of` carries `epoch.repo=="ep01"`; `ledger` has **no** `epoch` field.
- Flag if: a non-repo tool (ledger/savings/languages/list_repos/session_*) carries an epoch.

### EP04 — epoch is consistent across tools at one commit  `auto: EP04`
- Steps: envelopes of `outline`, `search_symbols`, `get_digest` for the same repo/commit.
- Expect: identical `{provider, commit, dirty:false}` on all three.
- Flag as INCONSISTENCY if they differ.

### EP05 — a commit moves the epoch; symbolId tools see the new commit  `auto: EP05`
- Steps: `recent_changes` epoch → add a method + commit → `recent_changes` epoch again → `get_digest`
  of the new symbol.
- Expect: the two commits differ; `get_digest` epoch equals the **new** commit.
- Flag if: the epoch does not move after a commit, or a symbolId tool reports the old commit. This is
  the property the cache depends on (epoch = live HEAD).

---

## 2. Response cache (Phase 3) — repeat deterministic reads are served from cache, keyed by epoch

Start a cache-enabled server (the default has the cache off):
```bash
# the automated harness passes --dylext.cache.enabled=true to a dedicated jar;
# for a manual run, restart Dylext with CACHE_ENABLED=true, or curl the gateway and read result.cached.
CACHE_ENABLED=true bash agents/tester/dylext.sh restart
```

### CA01 — a repeated read is a hit and byte-identical  `auto: CA01`
- Fixture: seeded repo `ca01`.
- Steps: call `search_symbols {repo, query:"F3"}` twice (read `result.cached` each time).
- Expect: first `cached:false`, second `cached:true`, identical payloads.
- Flag if: first call is already `cached:true` (stale warm-up), or payloads differ.

### CA02 — a new commit busts the cache  `auto: CA02`
- Steps: warm `search_symbols` (hit) → add method + commit → `search_symbols` again.
- Expect: after the commit, `cached:false` (recomputed).
- Flag as BUG if: still `cached:true` after a commit (serving stale data — the exact cursor-vs-HEAD trap).

### CA03 — dirty & disabled never cache  `auto: CA03`
- Steps: `working_changes` twice on the cache server; then `search_symbols` on the **default** server.
- Expect: `working_changes` has **no** `cached` field; the default server emits **no** `cached` field.
- Flag if: a `cached` flag appears for working_changes or on the cache-disabled server.

### CA05 — distinct args → distinct cache entries  `auto: CA05`
- Steps: cache `search F2` (miss→hit), then `search F3`.
- Expect: `F3` is a miss (`cached:false`) and returns F3's own payload, not F2's.
- Flag as BUG if: F3 is served F2's cached payload (key collision).

### CA06 — structural reads cache too  `auto: CA06`
- Steps: `get_digest {symbolId}` twice.
- Expect: miss then hit, identical payload.

### CA07 — bust **and** freshness: no stale hit after a commit  `auto: CA07`
- Steps: warm `outline F1.java` (hit) → add method `ca07New` + commit → `outline F1.java`.
- Expect: `cached:false` AND the recomputed outline **contains** `ca07New`.
- Flag as BUG if: `cached:true` (stale) or the new method is missing (recompute didn't freshness-sync).

---

## 3. Capability tiers (Phase 4b) — honest reporting + graceful degradation

### LT01 — `languages` reports an honest tier  `auto: LT01`
- Step: `agents/tester/dx languages '{}'`.
- Expect: every row has a `tier`; `java` = `T3`; running sidecars (python/go/typescript) = `T2`.
- Flag if: a row lacks a tier, or a tier value is not one of `T1`/`T2`/`T3`.

### LT02 — `impact_of` on a T3 (java) symbol has no `capabilityNote`  `auto: LT02`
- Step: `impact_of {symbolId:<a java symbol>}`.
- Expect: no `capabilityNote` key (byte-identical to pre-4b for T2/T3).
- Flag if: a note appears for a T2/T3 language.

### LT03 — a below-T2 language signals "capability unavailable", not a misleading empty result  (manual)
- Requires a T1 language. Configure a generic fallback sidecar (`--dylext.fallback-parser=<url>`), index a
  `.rb`/`.lua` file, then `impact_of` on one of its symbols.
- Expect: `impact_of` returns a `capabilityNote` explaining the symbol's language is T1 (structure only) —
  NOT a bare empty `callers:[]` that reads as "nothing calls this".
- Flag as GAP if: it returns empty callers with no note, or errors instead of degrading.
- Note: without a fallback sidecar this is covered by the unit test `LanguageRouterTest` +
  `ImpactServiceTest.impactBelowT2ReturnsACapabilityNoteInsteadOfAMisleadingEmptyResult`.

---

## 4. Runtime signals (Phase 4c) — coverage/call-frequency weights impact_of ranking

Reference fixture (`sgrepo`): a `Target.hot()` called by `CallerA.a()` and `CallerB.b()`.
```bash
d=$(mkrepo sg); 
# Target.java: class Target { public void hot(){} }
# CallerA.java: class CallerA { public void a(){ new Target().hot(); } }
# CallerB.java: class CallerB { public void b(){ new Target().hot(); } }
# git add/commit; then: agents/tester/dx index '{"repo":"sg","root":"'"$d"'"}'
```

### SG01 — signals reorder callers hot-and-untested first, and expose weight  `auto: SG01`
- Steps: find `Target#hot` symbolId; `impact_of` → note the two callers; then
  ```bash
  agents/tester/dx ingest_signals '{"repo":"sg","signals":[
    {"symbolId":"<CallerB#b>","coverage":0.0,"callFrequency":100},
    {"symbolId":"<CallerA#a>","coverage":1.0,"callFrequency":100}]}'
  ```
  then `impact_of` again.
- Expect: `callers[0]` is CallerB; `signalRanking` present, CallerB weight > CallerA weight.
- Flag if: order unchanged, or `signalRanking` absent/weights wrong.

### SG02 — no signals → no `signalRanking` (byte-identical to pre-4c)  `auto: SG02`
- Step: `impact_of` on a fresh repo (no ingest).
- Expect: payload has **no** `signalRanking` key.
- Flag if: the field appears without any ingest.

### SG03 — re-ingest is an upsert (updates weight, no duplicate)  `auto: SG03`
- Steps: ingest caller `coverage:0, callFrequency:10` → ingest same caller `callFrequency:20` → `impact_of`.
- Expect: one caller (not duplicated); `signalRanking[0].weight == 20`.
- Flag as BUG if: weight stays 10, or the caller appears twice.

### SG04 — `ingest_signals` validates input (-32602, never 5xx)  `auto: SG04`
- Steps (each must be an error): `{signals:[...]}` (no repo); `{repo,signals:[]}` (empty);
  `{repo,signals:[{coverage:0}]}` (no symbolId).
- Expect: JSON-RPC error code `-32602` for each.
- Flag as BUG if: any returns `-32603` (internal) or `200` success.

### SG05 — out-of-range coverage is clamped (weight never negative)  `auto: SG05`
- Steps: ingest a caller with `coverage:2.0, callFrequency:50`; `impact_of`.
- Expect: that caller's `signalRanking` weight is `0.0` (coverage clamped to 1.0 ⇒ `50*(1-1)=0`).
- Flag as BUG if: weight is negative or non-zero.

### SG06 — `impact_of` reflects a caller added by a later commit (freshness)  `auto: SG06`
- Steps: `impact_of Target#hot` (1 caller) → add `CallerB.java` + commit → `impact_of` again.
- Expect: 2 callers now, including CallerB — **without** an explicit `sync`.
- Flag as BUG if: still 1 caller (stale edges — impact_of must freshness-sync like the other reads).

### SG07 — signals are isolated per symbol id / repo  (manual)
- Steps: ingest a signal for a symbol in repo `A`; `impact_of` a same-named symbol in repo `B`.
- Expect: repo B's ranking is unaffected (ids embed the repo, so no cross-repo bleed).
- Flag as BUG if: B's result shows A's weight.

---

## 5. Cross-cutting consistency probes

### XC01 — a cached response still carries a correct `epoch` and `tokens` footer
- Step: on the cache server, call a cacheable read twice; inspect the envelope on the hit.
- Expect: the hit still has `epoch` (same commit) and a `tokens` footer.
- Flag as INCONSISTENCY if: the cached hit drops the epoch/tokens envelope.

### XC02 — mutations are never cached and never epoch-stale
- Step: `index` / `sync` / `ingest_signals` on the cache server.
- Expect: no `cached` field on any of them (they are not deterministic reads).
- Flag if: a mutation reports `cached`.

### XC03 — errors are not cached
- Step: `get_digest {symbolId:"<nonexistent>"}` twice on the cache server.
- Expect: both error (e.g. -32602); the second is not a cached success/stale error.
- Flag as BUG if: an error is cached and later served as data.

### XC04 — a non-git mutation (ingest_signals) invalidates the read cache  `auto: SG08`  [found F-017]
- Step: on the cache server, `impact_of` a symbol (caches it), then `ingest_signals` for one of its
  callers, then `impact_of` again.
- Expect: the second `impact_of` is `cached:false` and reflects the new `signalRanking`.
- Flag as BUG if: `cached:true` / ranking absent — the cache key is git HEAD, which `ingest_signals`
  does not move, so the mutation MUST explicitly flush the cache. (This was live-only: cache-off harness
  runs cannot see it.)

---

## 6. Repo meta — agent-authored purpose/intent notes (C′ half-1)

`set_summary` lets the agent attach a one-line purpose note to a symbol; `get_digest` surfaces it; the
note is stamped with the symbol's content hash so it drops when the symbol's contract changes. See
`docs/REPO-META-SKILL.md` for the analyze-before-ingest rule.

### SM01 — a note is stored, surfaced, and auto-invalidated on contract change  `auto: SM01`
- Fixture: `d=$(mkrepo sm)`; write `F1.java` (`package p; public class F1 { public int base(){ return 1; } }`),
  commit, `index`, then `search_symbols {repo, query:"base"}` to get the `base()` symbolId.
- Steps:
  1. `set_summary {symbolId, text:"orchestrates the F1 request flow"}` → expect `{stored:true}`.
  2. `get_digest {symbolId}` → expect `summary.text` == the note.
  3. Change `base`'s contract so its hash changes (e.g. it now calls a new `helper()`), commit.
  4. `get_digest {symbolId}` again.
- Expect: step 4's `summary` is absent or not the old note (the stale note is not served); a body-only
  edit that leaves the signature/doc/call-edges unchanged would NOT invalidate (hash = contract).
- Flag if: the note is not surfaced (step 2), the stale note is served after the contract change
  (step 3–4), or bad input (unknown symbolId / blank text) is not a `-32602`.

---

## 7. Sestacon document ingestion (A1 formats · A2 images · A3 versioning)

Call Sestacon via the gateway (`:18070/api/*`) or directly (`:18080`). Auto mate for these:
`e2e/sestacon_probe.py` (`bash agents/tester/sestacon.sh probe`).

### SA14 — a widened format (HTML/CSV/JSON/YAML/Office) is accepted and retrievable  `auto: SA14`
- Step: `POST /api/ingest` a `.html` file (`Content-Type: text/html`) containing a unique word, then
  `POST /api/retrieve` for that word.
- Expect: ingest 200 (not 415) and the word is retrievable. (The runtime allowlist lives in
  `application.yml`, not just the Java default — both must list the type.)
- Flag if: 415 for an allow-listed type, or the content is not retrievable.

### SA15 — image ingestion is disabled (415); OCR/surrogate code retained  `auto: SA15`
- Step: `POST /api/ingest` a PNG (`Content-Type: image/png`).
- Expect: 415 — image types are intentionally not allow-listed (no OCR backend). The surrogate/OCR
  path in `TikaDocumentParser` is kept in code; re-enable by adding the image types to the allowlist
  (and configuring tesseract).
- Flag if: an image is accepted (200) while OCR/image support is meant to be off.

### SA16 — re-ingest reports the version change: NEW then UNCHANGED  `auto: SA16`
- Step: ingest a doc (read `results[0].change.status`), then ingest the identical bytes again.
- Expect: first `status:"NEW"`, second `status:"UNCHANGED"` (and the second does not re-embed).
- Flag if: the `change` descriptor is missing, or identical content is not reported UNCHANGED.

### SA17 — changed content reports a non-UNCHANGED change + a new version hash  `auto: SA17`
- Step: ingest a doc; `GET /api/documents/{id}/version` (docHash1); ingest changed content for the same
  id; `GET …/version` again (docHash2).
- Expect: `change.status` ∈ {PARTIAL, FULL}, `changedSections` non-empty, and `docHash1 != docHash2`.
- Flag if: a content change is reported UNCHANGED, or the version hash does not move.

### SA18 — version of an unknown document is 404  `auto: SA18`
- Step: `GET /api/documents/<unknown>/version`.
- Expect: 404 (not an empty 200).
- Flag if: a missing document returns 200.

### SA19 — chunk-ingest (`POST /api/documents`) reports a version change like the file path  `auto: SA19`
- Step: POST a chunk array for a documentId (read `changes[0].status`), then POST the identical array again.
- Expect: first `status:"NEW"`, second `status:"UNCHANGED"`; `changes[]` is per-document.
- Flag if: the chunk path lacks a `changes` descriptor, or identical chunks are not reported UNCHANGED.

---

## Appendix — mapping to the automated harness
`e2e/dev_activity.py` runs the Dylext `auto:` scenarios headless against a throwaway Postgres
(`bash e2e/run.sh --strict`); `e2e/sestacon_probe.py` runs the Sestacon `SA*` scenarios
(`bash agents/tester/sestacon.sh probe --strict`). This runbook is the manual/AI path for the same
coverage plus the manual-only probes (LT03 fallback, SG07 isolation, XC01–XC03). Keep the two in sync:
when you add a scenario here, add or update its `auto:` mate (and vice-versa).
