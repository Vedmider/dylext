# API decomposition — Sestacon & Dylext

Stage 1 of 3. Purpose: split each app by API use case, then subdivide each use case into
ATOMIC logical stages so every stage can be reviewed in isolation for gaps/quality.
Evidence gathered via Dylext/Sestacon tools (outline/expand) against the live index.

- Heads read: sestacon `7fdc771`, dylext `b9aad3d`.
- Stage 2 (review notes, no code change) lives in `REVIEW-NOTES.md`.
- Stage 3 (improvements) only after you approve the review.

Legend: each atomic stage is `S<n>` with the owning symbol. "→" = hand-off to the next layer.

---

# SESTACON — HTTP/RAG API

Hexagonal: `adapter/in/web` (controllers) → `domain/port/in` (use cases) →
`application/*Service` → `domain/port/out` (DocumentStore, DocumentVersionStore, ChatProvider,
EmbeddingProvider, AnswerCache) → `adapter/out` (pg/vector/redis/tika/openai).

Cross-cutting (apply to every request, not a use case):
- X01 RequestCorrelationFilter — request id / MDC correlation (logging).
- X02 RateLimit filter — token-bucket per client, trusted-proxy XFF resolution (F-031).
- X03 RoleHeader.parse(X-Roles) — RBAC roles → ACL scoping, case-sensitive (F-021).
- X04 GlobalExceptionHandler — GuardrailException→400, Exception→500, RFC-7807 ProblemDetail.
- X05 Guardrails (RagQueryService-internal): PromptInjectionDetector, PiiRedactor, OutputGuard.

## UC-S1  POST /api/ingest  (multipart file upload)
Entry: `IngestController#ingest(MultipartFile[],List<String> aclTags,String sourceSystem)`
→ `DocumentIngestionService#ingestWithVersion(filename,content,aclTags,sourceSystem)` (@Transactional)

- S1  empty-batch guard — `files.length==0` → 400.                     [IngestController#ingest]
- S2  per-file validation — empty / size>max / content-type∉allowed → 400/413/415 + SecurityAudit. [IngestController#validate]
- S3  read bytes — `file.getBytes()`; IOException → 400 + audit.        [IngestController#ingest]
- S4  parse — Tika extract → List<DocumentChunk> (chunking by section). [DocumentParser.parse]
- S5  acl stamping — overwrite each chunk's aclTags when provided.      [ingestWithVersion]
- S6  enrich — MetadataEnricher adds sourceSystem + metadata.           [MetadataEnricher.enrich]
- S7  contextual chunking (optional) — ContextualChunker.contextualize. [ingestWithVersion]
- S8  hashing — per-section ContentHash.ofSection(content, aclTags).    [ingestWithVersion]
- S9  per-document advisory lock — documentStore.lockDocuments.         [ingestWithVersion] (F-034)
- S10 version diff — versionStore.diff(documentId, sectionHashes); UNCHANGED → short-circuit. [ingestWithVersion]
- S11 replace — documentStore.replace(chunks, aclTags) (embed + store). [DocumentStore.replace]
- S12 record version — versionStore.record(id, chunkHash, size, hashes). [ingestWithVersion]
- S13 cache bust — answerCache.invalidateAll (F-022).                   [ingestWithVersion]
- S14 response assembly — per-file {file,chunks,change{status,changedSections}}, 200. [IngestController#ingest]

## UC-S2  POST /api/documents  (pre-chunked ingest)
Entry: `DocumentController#ingest(List<ChunkRequest>,String sourceSystem)`
→ `ChunkIngestionService#ingestWithVersions(chunks,sourceSystem)`

- S1  payload→domain — ChunkRequest.toDomain() per chunk.               [DocumentController.ChunkRequest#toDomain]
- S2  enrich — MetadataEnricher.enrich.                                 [ingestWithVersions]
- S3  contextual chunking (optional).                                   [ingestWithVersions]
- S4  hashing + lock + diff + replace + record + cache-bust (same spine as UC-S1 S8–S13). [ingestWithVersions]
- S5  response — {documents, chunks, perDocument change}.               [DocumentController#ingest]

## UC-S3  DELETE /api/documents/{id}
Entry: `DocumentController#delete(documentId, X-Roles)` → `DeleteDocumentService#delete(id,roles)`

- S1  role parse / ACL scope — only delete docs visible to roles.       [RoleHeader + delete]
- S2  delete chunks — documentStore delete by documentId (role-filtered). [delete]
- S3  version tombstone / record — versionStore.                        [delete]
- S4  cache bust — answerCache.invalidateAll.                           [delete]
- S5  response — {deleted:<count>}; unknown id → count 0.               [DocumentController#delete]

## UC-S4  GET /api/documents/{id}/version   (note: path is `/version`, singular)
Entry: `DocumentController#version(id)` → `DocumentVersionService#version(id)`

- S1  lookup — versionStore → Optional<DocumentVersion>.                [DocumentVersionService#version]
- S2  present/absent mapping — 200 body / 404.                          [DocumentController#version]

## UC-S5  POST /api/retrieve
Entry: `RetrieveController#retrieve(RetrieveRequest, X-Roles)` → `RetrieveService#retrieve(query,topK,roles[,filter])`

- S1  query validation — blank → 400.                                   [RetrieveController#retrieve]
- S2  topK clamp — null/≤0 → default; cap at MAX_TOP_K.                  [RetrieveController#effectiveTopK]
- S3  role parse.                                                       [RoleHeader.parse]
- S4  widen — wide = max(topK,1) * recallFactor.                        [RetrieveService#retrieve]
- S5  first-pass search — documentStore.search(query,wide,roles,filter) (hybrid: vector+FTS; ACL+metadata). [DocumentStore.search]
- S6  rerank — Reranker.rerank(query,candidates,topK) (IDF overlap + vector, unicode-aware; F-028). [Reranker.rerank]
- S7  relevance floor — drop score<minScore (no-match ⇒ []; F-004/F-013). [RetrieveService#retrieve]
- S8  source-token enrichment — withSourceTokens (cached per source; F-033). [RetrieveService#retrieve]
- S9  response — List<RetrievedChunk> with scores.                      [RetrieveController#retrieve]

## UC-S6  POST /api/chat
Entry: `ChatController#chat(ChatRequest, X-Roles)` → `RagQueryService#answer` → `#doAnswer(question,roles)`

- S1  input validation — blank → 400; length>max → 400.                 [ChatController#chat]
- S2  prompt-injection guard (input) — block → GuardrailException(400). [doAnswer]
- S3  PII redaction (input).                                            [doAnswer / PiiRedactor]
- S4  embed question.                                                   [EmbeddingProvider.embed]
- S5  semantic answer-cache lookup — findSimilar(embedding,roles) (role+config-scoped; F-021/F-032). [AnswerCache.findSimilar]
- S6  retrieve context — retrieve.retrieve(question,topK,roles) (= UC-S5 spine). [RetrieveUseCase]
- S7  context injection filter — drop chunks flagged as injection.      [doAnswer]
- S8  relative context cut — keep score ≥ contextRelativeCut*top (F-029). [doAnswer]
- S9  prompt build — USER_TEMPLATE.formatted(context,question).         [doAnswer]
- S10 LLM complete — chatProvider.complete(systemPrompt,userPrompt) + token metrics. [ChatProvider.complete]
- S11 output guard — OutputGuard.sanitize.                              [doAnswer]
- S12 PII redaction (output).                                           [doAnswer]
- S13 refusal detection — Refusal.isRefusal ⇒ sources=[] , confident=false (F-012/F-029). [doAnswer]
- S14 cache store — answerCache.store(question,embedding,answer,sources,roles). [AnswerCache.store]
- S15 response — Answer{text,sources,cached,confident[,contexts]}.      [doAnswer]

---

# DYLEXT — MCP tool API (JSON-RPC: stdio + HTTP `/mcp`)

Hexagonal: `adapter/in/mcp` (StdioGuard, McpHttpController, McpDispatcher) → `domain/port/in`
use cases → `application/*Service` → `domain/port/out` (DigestStore, VcsClient, SignalStore,
Tokenizer, SkeletonExtractor via LanguageRouter) → `adapter/out` (pg, jgit, parser sidecars).

## Transport / dispatch pipeline (cross-cutting, wraps every tool)
Entry: `McpDispatcher#handle(requestJson)`

- T1  stdout purity — StdioGuard claims stdout; logs/banner → stderr (F-001). [StdioGuard]
- T2  JSON-RPC parse — bad JSON → -32700.                               [handle]
- T3  method routing — initialize | tools/list | tools/call | ""→-32600 | else -32601. [handle]
- T4  error taxonomy — IllegalArgument/NoSuchElement→-32602 (caller), else -32603 (server). [handle]
- T5  tool dispatch — name+arguments → computePayload.                  [callTool]
- T6  epoch stamp — epochFor(name,args) for EPOCH_TOOLS (git HEAD).     [callTool/epochFor]
- T7  response cache — cacheKey=(tool,args,epoch); get/put; `cached` flag (F-016/F-018). [callTool/cacheKey]
- T8  mutation invalidation — MUTATION_TOOLS → cache.invalidateAll (F-017). [callTool]
- T9  ledger stamp — attach ledger.totals() to every result.           [callTool]
- T10 result envelope — content[].json + tokens + epoch + cached.      [callTool]

Freshness-on-read (cross-cutting, inside read services): `FreshnessGate#ensureFresh(repo)` —
HEAD≠cursor OR generation<CURRENT ⇒ sync; sync failure is best-effort + logged (F-016/F-018/F-025).

## Tool groups & their atomic stages

### G-A Catalog / discovery  (list_repos, repo_overview, languages)
- repo_overview: A1 ensureFresh → A2 catalog read (files/symbols/langs/modules/topLevel; F-006 count rule) → A3 assemble.
- languages: A1 for each configured lang probe sidecar health (TTL cache) → A2 available=healthy (F-009).
- list_repos: A1 read repo_state rows (non-module symbol count).

### G-B Code context  (outline, search_symbols, get_digest, expand)
- outline: B1 ensureFresh → B2 requireIndexed → B3 symbols-by-file (unknown path → -32602; F-008) → B4 ledger record (whole-file baseline; F-007) → B5 digests.
- search_symbols: B1 ensureFresh → B2 fuzzyTokens(query) → B3 lexical match (+pathPrefix) → B4 topK clamp → B5 ledger record → B6 digests.
- get_digest: B1 ensureFresh(repoOf) → B2 fetch symbol + summary → B3 ledger record.
- expand: B1 ensureFresh → B2 fetch symbol → B3 read raw span → B4 ledger record (span vs whole-file).

### G-C Blast radius  (impact_of)
- C1 ensureFresh(repoOf) (F-016) → C2 fetch symbol → C3 if type: aggregate members' edges (F-005) →
  C4 resolve callers+tests via edges (language/receiver/arity filtered; F-024) → C5 signal ranking (F-007-ledger) →
  C6 limit cap (truncated+totalCallers) → C7 ledger record (whole-file baseline).

### G-D Git / live state  (recent_changes, working_changes)
- D1 ensureFresh → D2 VcsClient diff (recent: commits; working: uncommitted vs HEAD) → D3 shape.

### G-E Index lifecycle  (index, sync)
- index: E1 resolve root dir → E2 detect repo-root re-point → clearRepo → E3 generation check (rebuild-all if stale; F-025) →
  E4 per-file LanguageRouter.route {PARSE|UNSUPPORTED|UNREACHABLE} (F-009/F-010) → E5 extract skeleton + call edges →
  E6 upsert symbols + prune removed → E7 reopenEdgesTo (re-resolve same-name; F-026) → E8 resolvePendingEdges →
  E9 save generation + cursor(HEAD) → E10 metrics + IndexResult tally.
- sync: E1 cursor present & reachable? (hasCommit; F-020) → E2 reachable ⇒ diff-since changed files; unreachable/absent ⇒ full index →
  E3 same per-file spine → E4 save cursor.

### G-F Verify  (verify_claims)
- F1 extract candidate tokens from answer (cap MAX_TOKENS) → F2 per token lexical match in index →
  F3 verified(id,signature,path,line,#matches) | unverified → F4 count unverified → VerifiedAnswer.

### G-G Signals  (ingest_signals)
- G1 parse signals[{symbolId,coverage,callFrequency}] → G2 SignalStore upsert → G3 (MUTATION) cache bust (F-017).

### G-H Session  (session_start/fact/commit/recall/list/close)
- H1 start → id. H2 fact → ring-buffer (bounded, mechanical). H3 commit → durable decision entries.
  H4 recall → decisions + newest facts (full/sections). H5 list → headers. H6 close → status.

### G-I Ledger  (ledger)
- I1 totals() = served tokens vs whole-file baseline accumulated by read tools (F-007/F-016).

### G-J Annotate  (set_summary)
- J1 store agent note stamped with symbol content hash (auto-invalidates on code change).

---

# CROSS-SYSTEM federation (agent-tier)
Dylext is pure code context; it does NOT call Sestacon. The AGENT blends:
exact structure (Dylext search/outline/expand/impact) + prose (Sestacon /retrieve or /chat) +
grounding (Dylext verify_claims). `explain/answer/recall` were removed in the two-app split (F-015 O03).
