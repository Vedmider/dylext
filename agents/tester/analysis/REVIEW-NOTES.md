# Review notes — per atomic chunk (Stage 2, REVIEW ONLY)

No code changed. Each note is an observation against the stage ids in `API-DECOMPOSITION.md`.
Tags: `[OK]` sound; `[COVERED F-nnn]` already addressed by a finding; `[OBS]` neutral observation;
`[CANDIDATE]` possible gap/improvement needing verification + your approval before any Stage-3 change.
Hypotheses not yet reproduced are marked `(inferred)`.

Verification I did: read the method bodies via `expand` (heads sestacon 7fdc771, dylext b9aad3d).
I did NOT run new repros for the CANDIDATEs below — that is the first Stage-3 step once you approve.

---

## SESTACON

### UC-S1  POST /api/ingest
- S1 empty-batch `[OK]`.
- S2 validate `[OK]` — empty/size/content-type each audited; 400/413/415 correct (matches SX01/02 PASS).
- S3 read bytes `[OBS]` — only `IOException` is caught→400. A Tika failure in S4 is a different path.
- S4 parse `[CANDIDATE]` (inferred) — if `DocumentParser.parse` throws on a corrupt-but-allowed file
  (e.g. a truncated PDF with a valid content-type), nothing in `ingestWithVersion` catches it, so it
  surfaces as 500 via GlobalExceptionHandler rather than a 400/422 "unparseable". Verify: ingest a
  content-type=application/pdf payload of random bytes; expect current=500. Proposal (later): map parse
  failures to 422 + audit, consistent with S2.
- S5 acl stamping `[OK]`.
- S6/S7 enrich/contextual `[OK]`.
- S8 hashing `[OK]` — ofSection includes aclTags (F-023), so acl-only changes are not deduped away.
- S9 lock `[COVERED F-034]`.
- S10 diff/UNCHANGED short-circuit `[OK]` — unchanged path returns before cache bust, which is correct
  (no corpus change ⇒ cache stays valid; consistent with F-022).
- S11 replace / S12 record `[OK]` — inside @Transactional with S9 lock.
- S13 cache bust `[COVERED F-022]`.
- S14 response `[OBS]` — `documentId = chunks.get(0).documentId()` assumes ONE document per file. If a
  parser ever emits chunks with differing documentIds, the lock/diff/version would key off only the
  first. `[CANDIDATE]` (inferred): confirm the parser guarantees a single documentId per file; if not,
  group by documentId before S8–S12. Verify: inspect DocumentParser output for a multi-section file.

### UC-S2  POST /api/documents  (pre-chunked)
- S1 toDomain `[OK]`.
- S4 spine `[OK]` — same lock/diff/replace/record/bust as UC-S1 (F-034/F-022 apply).
- `[CANDIDATE]` (inferred) — unlike UC-S1 there is NO size/count validation on the pre-chunked payload
  (no per-chunk length cap, no max chunk count, aclTags optional). A client can push arbitrarily large
  or unbounded chunk lists straight to embed+store. Verify: POST /api/documents with a very large chunk
  list / huge single chunk; observe memory + whether it is bounded anywhere. Proposal (later): mirror
  IngestProperties limits for the chunk endpoint. (Relates to open catalog item SX10.)

### UC-S3  DELETE /api/documents/{id}
- S1 role scope / S2 delete `[OK]` — ACL-scoped (no cross-role delete; consistent with F-021/SX05).
- S5 response `[CANDIDATE]` (inferred) — `delete` returns a count; deleting a non-existent id AND
  deleting a doc the caller's roles cannot see both yield `deleted:0` + 200. A caller cannot distinguish
  "not found" from "forbidden/invisible". Verify: DELETE an id that exists under a role the caller lacks
  vs a truly unknown id. Proposal (later): decide the intended contract (keep opaque 0 for ACL privacy,
  or 404 for unknown) — privacy argument favours leaving it opaque; document it either way. (SX10.)

### UC-S4  GET /api/documents/{id}/versions
- S1/S2 `[OK]` — Optional→200/404.
- `[OBS]` — no ACL check on version metadata read; version info (size, section hashes) is returned
  regardless of roles. `[CANDIDATE]` (inferred): if version metadata is considered sensitive, this leaks
  existence/structure of ACL-restricted docs. Verify intended sensitivity with the Sestacon ACL model.

### UC-S5  POST /api/retrieve
- S1 validation / S2 topK clamp `[OK]` — MAX_TOP_K cap present.
- S4 widen `[OK]`.
- S5 search `[COVERED F-011]` (metadata filter now honored in vector mode too).
- S6 rerank `[COVERED F-028]`.
- S7 floor `[COVERED F-004/F-013]` — note the accepted precision/recall trade-off (pure paraphrase with
  zero shared terms can fall below the floor).
- S8 source-token enrichment `[COVERED F-033]` — note the OPEN tail: vector-mode `PgVectorDocumentStore.
  sourceTokens` still runs a 10k-topK similarity per source (F-033 open). `[CANDIDATE]` perf, already logged.
- S9 response `[OK]` — exposes vectorScore/lexicalScore (F-028).

### UC-S6  POST /api/chat
- S1 validation `[OK]`.
- S2 injection guard / S3 PII in `[OK]`.
- S4 embed `[OK]` — embedded once, reused for retrieve and cache (no double embed).
- S5 answer-cache lookup `[COVERED F-021/F-032]` — role key exact+collision-free; namespace fingerprints config.
- S6 retrieve `[OK]` — reuses UC-S5 spine (floor applies).
- S7 context injection filter `[OK]`.
- S8 relative cut `[COVERED F-029]`.
- S9 prompt build `[OBS]` — single USER_TEMPLATE; context joined with `\n---\n`. No explicit per-chunk
  source labelling inside the prompt; citations are reconstructed from chunk sources post-hoc (S13).
  `[CANDIDATE]` (inferred): the LLM cannot attribute which claim came from which source, so a
  multi-source answer's `sources` list is "all surviving chunks", not "the ones actually used". Verify
  against judge eval (F-030) precision. Proposal (later): numbered context blocks + ask the model to cite.
- S10 LLM complete `[OK]` — token metrics recorded.
- S11 output guard / S12 PII out `[OK]`.
- S13 refusal detection `[COVERED F-012/F-029]`.
- S14 cache store `[OK]`.
- S15 response `[OBS]` — the NON-cached path returns `Answer` WITH `contexts`; the CACHED-hit path (S5)
  builds `Answer` with the 4-arg ctor and NO contexts. `[CANDIDATE]` (inferred): judge/verify tooling
  that reads `Answer.contexts` (F-030) gets empty contexts on a cache hit, skewing grounding metrics for
  cached answers. Verify: chat the same question twice, inspect server-side contexts on the 2nd (cached)
  response. Proposal (later): cache the contexts too, or exclude cache hits from judge sampling.

### Cross-cutting
- X02 rate limit `[COVERED F-031]`.
- X03 role header `[COVERED F-021]`.
- X04 exception handler `[OK]` — RFC-7807 consistent (SX13 PASS). Tie-in: if S4-parse (UC-S1) should be
  422 not 500, the mapping lives here.

---

## DYLEXT

### Transport / dispatch (T1–T10)
- T1 stdout purity `[COVERED F-001]`.
- T2 parse / T3 routing / T4 error taxonomy `[OK]` — clean -32700/-32600/-32601/-32602/-32603 split.
  `[OBS]` a `tools/call` for an unknown tool name routes into computePayload — confirm it yields -32602
  (caller) not -32603; (covered indirectly by F-015 O03 but worth an explicit assert). `[CANDIDATE]` test-only.
- T6 epoch / T7 cache `[COVERED F-016/F-018]`.
- T8 mutation invalidation `[COVERED F-017]`.
- T9 ledger stamp `[COVERED F-007]`.
- T10 envelope `[OK]`.

### Freshness-on-read (FreshnessGate.ensureFresh)
- `[COVERED F-016/F-018/F-025]` for the "which reads call it" and "generation bump" gaps.
- `[CANDIDATE]` (inferred, design) — on sync failure the gate is best-effort: it logs + increments
  `dylext.freshness.sync.failures` and serves the STALE index, but the TOOL RESPONSE carries no
  staleness signal. A caller/agent cannot tell the answer is stale (only ops can, via metrics). Same
  class as F-020's resolution intent ("staleness must be visible") but that visibility is server-side
  only. Proposal (later): surface a `stale:true`/`cursorBehind` hint in the result envelope when the
  last ensureFresh for that repo failed. Verify: force a sync failure (e.g. unreadable root) and inspect
  the next read's payload.
- `[OBS]` first read after N new commits blocks on inline sync (load-test note: ~5s). Known OPEN perf item.

### G-B Code context
- outline B3 unknown-path→-32602 `[COVERED F-008]`.
- search_symbols B2 fuzzyTokens `[OK]` — note the FUZZY behaviour bit the harness (F-015); not an app bug,
  but `[CANDIDATE] docs`: the tool description says "exact lexical" while matching is fuzzy token-based —
  the contract wording and behaviour diverge. Verify by reading the tool desc vs fuzzyTokens; align docs.
- ledger baseline B4 `[COVERED F-007]`.

### G-C impact_of
- C1 freshness `[COVERED F-016]`.
- C3 aggregate `[COVERED F-005]`.
- C4 edge resolution `[COVERED F-024/F-026]`.
  `[CANDIDATE]` (inferred) — F-024 "leaves Java arity mismatches unresolved" is deliberately conservative;
  varargs and autoboxing/widening calls (e.g. `f(1)` to `f(long...)`) have a mismatching raw arity and
  would be dropped, under-reporting callers. Verify: fixture with a varargs/overload-by-widening caller;
  check impact_of lists it. Proposal (later): arity match that accounts for varargs/widening.
- C5 signal ranking `[OK]`. C6 limit cap `[OK]`.

### G-E index / sync
- E3 generation rebuild `[COVERED F-025]`.
- E7/E8 edge re-resolution `[COVERED F-026]`.
- E2(sync) reachable-cursor `[COVERED F-020]`.
- `[OBS]` cross-repo call edges are unresolved by design (name resolution is per-repo). Not a gap;
  document as a known boundary so verify_claims/impact_of expectations are calibrated.

### G-F verify_claims
- F2/F3 `[OK]` but `[CANDIDATE]` (inferred) — on multiple matches it reports `matches.get(0)` as THE
  verified symbol (plus a `#matches` count). For a homonym across modules the surfaced id/signature may
  be the wrong one even though the count hints at ambiguity. F-012 marked the broad precision work done;
  this specific "first-match-wins display" is worth an explicit check. Verify: answer citing a name that
  exists in two modules; inspect which id is returned. Proposal (later): return all candidates (or mark
  AMBIGUOUS) when matches>1 instead of silently picking the first.
- F1 token cap `[OBS]` — MAX_TOKENS truncates long answers; citations beyond the cap are silently not
  verified. `[CANDIDATE] docs`: state the cap in the tool contract so a long answer isn't falsely "all verified".

### G-G signals / G-H session / G-I ledger / G-J annotate
- `[OK]` per earlier findings (F-017 bust, F-006/F-007 ledger). No new observations from the read.

---

## Summary of CANDIDATES to triage (Stage 3, pending approval)
Priority is my suggestion; all are review-only hypotheses until reproduced.

1. (S, med) UC-S1 S4 — parser failure on corrupt allowed-type file → 500 instead of 4xx. Verify+map to 422.
2. (S, med) UC-S2 — no size/count limits on the pre-chunked /api/documents endpoint (unbounded intake). (SX10)
3. (S, low) UC-S3 S5 — delete count conflates not-found vs forbidden; decide+document the contract. (SX10)
4. (S, low) UC-S4 — version metadata read has no ACL check; confirm sensitivity.
5. (S, med) UC-S6 S9/S15 — prompt has no per-source labels; cached answers drop `contexts` (skews judge, F-030).
6. (D, med) Freshness — stale-on-sync-failure is invisible in the tool response; surface a `stale` hint.
7. (D, low) impact_of C4 — varargs/widening callers dropped by strict arity; broaden match.
8. (D, low) verify_claims F2 — first-match-wins display on homonyms; return/flag ambiguity. Plus token-cap doc.
9. (D, docs) search_symbols — "exact lexical" wording vs fuzzy behaviour; align contract.

None of the above is confirmed as a defect yet — each needs a repro before it becomes an F-nnn finding.


---

# STAGE 2b — ARCHITECTURE PRESSURE TEST (live attacks on the CANDIDATEs)

Each candidate was attacked against the running apps (gateway :18070). Verdicts below.
Still REVIEW ONLY — no app code changed. Fixtures were ingested then deleted; `tester-arity`
repo indexed in dylext. New confirmed defects are listed as PT-nnn for promotion to FINDINGS
once you approve Stage 3.

| # | Candidate (as written) | Verdict | Evidence |
|---|---|---|---|
| 1 | ingest corrupt allowed-type → 500 | REFUTED → **worse defect found (PT-01)** | garbage-as-PDF and garbage-as-DOCX both 200 |
| 2 | /api/documents unbounded intake | **CONFIRMED (PT-02)** | 4 MB chunk 200; 3000 chunks 200 in 14 s; no cap |
| 3 | delete conflates not-found vs forbidden | REFUTED → **reframed to ACL oracle (PT-03)** | forbidden→403 naming the id; unknown→200 deleted:0 |
| 4 | /version no ACL check | **CONFIRMED (PT-04)** | anon read of FINANCE doc returns hashes+size, 200 |
| 5 | chat cached path drops contexts | CONFIRMED by code (not HTTP-observable) | contexts is @JsonIgnore; cached ctor omits it |
| 6 | Dylext stale-on-sync-failure invisible | **CONFIRMED by construction (PT-05)** | result envelope = content/tokens/epoch only, no `stale` |
| 7 | impact_of drops varargs/widening callers | **CONFIRMED, narrowed to varargs (PT-06)** | varargs callers = []; widening (equal arity) resolves |
| 8 | verify_claims first-match-wins on homonym | CONFIRMED, LOW | pins first id BUT surfaces `candidates:3` |
| 9 | search_symbols "exact lexical" vs fuzzy | **CONFIRMED (PT-07)** | truncated/misspelled queries return fuzzy + noise hits |

## Confirmed defects (promotion candidates to FINDINGS, pending approval)

### PT-01  Empty/garbage extraction is stored as a retrievable chunk scoring 1.0  [sev M] [app sestacon]
Attack: `POST /api/ingest` with 2 KB of `/dev/urandom` typed `application/pdf` (and the same as
`application/vnd...wordprocessingml.document`). Both → 200, `chunks:1`. Retrieve `tester-corrupt`
returned the chunk at **score 1.0**, body = only the contextual header `[tester-corrupt.pdf > chunk-0]`.
Root cause (inferred): UC-S1 has no "did parsing yield usable text?" gate between S4 (parse) and
S10–S11 (diff/store); `file.isEmpty()` (S2) checks the UPLOAD size, not extracted text, and
ContextualChunker (S7) prepends the filename header so an empty body still looks non-empty and matches
filename queries — defeating the F-004/F-013 relevance floor. Impact: index pollution; binary uploads
become confident retrieval hits. Fix direction (later): drop/again-reject chunks whose post-parse,
pre-context text is blank; or require a minimum extracted-text length.

### PT-02  `/api/documents` has no size/count limits (unbounded intake)  [sev M] [app sestacon]
Attack: one 4 MB chunk → 200; 3000 chunks → 200 (14 s, 3000 embeds). The file endpoint enforces
`maxFileSizeBytes` + content-type (S2); the chunk endpoint enforces neither. DoS / cost surface.
Catalog tie-in: SX10. Fix direction (later): mirror IngestProperties caps (max chunk count, max
per-chunk + total bytes) on DocumentController#ingest.

### PT-03  Forbidden-delete 403 is an existence/ACL oracle  [sev M] [app sestacon]
Attack: anon `DELETE /api/documents/tester-secret.txt` (doc is FINANCE-restricted) → **403** with
detail `insufficient roles to delete all chunks of document: tester-secret.txt`; unknown id →
`200 {deleted:0}`. So an unauthenticated caller can enumerate which restricted document ids exist by
distinguishing 403 from 200/deleted:0 — and the 403 body echoes the id. Lineage: same ACL-privacy class
as F-021/F-023. Fix direction (later): for docs the caller cannot see, return the same opaque result as
unknown (200 deleted:0 or 404) and do not echo the id in the detail.

### PT-04  `GET /api/documents/{id}/version` leaks version metadata of ACL-restricted docs  [sev M/H] [app sestacon]
Attack: anon `GET /api/documents/tester-secret.txt/version` (FINANCE doc) → **200** with `docHash`,
`sizeBytes`, `sectionHashes`, `chunkCount`. `DocumentController#version(@PathVariable String)` takes NO
roles and `DocumentVersionService#version` does no ACL filtering. Leaks existence + structure + content
hashes (hash enables confirm-by-guess on low-entropy content). Fix direction (later): require X-Roles
and ACL-filter like delete/retrieve; 404 when not visible.
Note: my decomposition first said path `/versions` — it is `/version` (singular); corrected in
API-DECOMPOSITION.md UC-S4.

### PT-05  Dylext serves a stale index after a freshness-sync failure with no signal to the caller  [sev M] [app dylext]
Confirmed structurally: the tools/call result envelope is `{content, tokens, epoch[, cached]}` — no
staleness field. On `FreshnessGate.ensureFresh` sync failure the read proceeds on the old index and only
a log line + `dylext.freshness.sync.failures` metric record it (server-side). F-020's intent
("staleness must be visible") is satisfied for ops, not for the agent consuming the tool. Fix direction
(later): add a `stale:true`/`cursorBehind` hint to the envelope when the last ensureFresh for the repo
failed.

### PT-06  impact_of reports ZERO callers for varargs methods  [sev M] [app dylext]
Attack (`/tmp/tester-arity`): `static void log(String... xs)` is stored as `Log#log(String)`; callers
`log("a","b","c")` and `log()` both dropped → `impact_of` callers = **[]**. Control: `note(long)` called
as `note(5)` (equal arity, int→long widening) resolves correctly. So F-024's conservative arity match
drops varargs calls (call arity ≠ declared arity). Any varargs method (logging, format, builders) looks
unused — dangerous for blast-radius-driven refactors. Fix direction (later): treat a trailing varargs
param as matching N≥(declared-1) args.

### PT-07  `search_symbols` contract says "exact lexical" but matching is fuzzy and noisy  [sev L, docs+relevance] [app dylext]
Attack: `ContextServic` (truncated) → 8 hits incl ContextService; `ImpaktService` (misspelled) → 10 hits
that are unrelated `service`/`SERVICE` symbols, NOT ImpactService. The tool description
("Find a symbol by known name/identifier (exact lexical)") contradicts behaviour (confirmed earlier in
F-015 harness fallout). Two sub-issues: (a) doc/contract wording; (b) a misspelled class name ranks
unrelated sub-token matches first instead of the intended symbol. Fix direction (later): align the
description, and/or rank exact/prefix matches above split-token fuzzy matches.

## Downgraded / closed by the attack
- Candidate #1 as written (500 on corrupt file): REFUTED — Tika never throws here; replaced by PT-01.
- Candidate #3 as written (conflation): REFUTED — delete DOES return 403 vs 200; reframed to PT-03 oracle.
- Candidate #8 (verify_claims): CONFIRMED but LOW — `candidates:N` already signals ambiguity; only the
  displayed first-match id is imprecise. Treat as optional polish, not a defect.

## Net: 7 confirmed defects (PT-01..PT-07), all sev M except PT-07 (L). None fixed — awaiting approval.
Severity order for Stage 3: PT-04 ≈ PT-03 (ACL leaks) > PT-01 (index pollution) ≈ PT-02 (DoS) ≈
PT-06 (blast-radius correctness) ≈ PT-05 (stale visibility) > PT-07 (docs/relevance).
