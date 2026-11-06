# Gap analysis — rag-app & code-memory

Analyzed via the live code-memory index (cmrag-app :8080, repo-scoped) plus direct source reads.
No running app was restarted; a throwaway analysis container was used and removed.

## rag-app

**G1 — Retrieval is single-shot, no rerank (confirmed in source).**
`RetrievalService.retrieve(query, topK, roles)` delegates straight to `documentStore.search(...)`.
No cross-encoder rerank, no query transform/expansion, no faithfulness/grounding check. This is the
#1 quality gap vs real-world RAG. Fix path: `Reranker` out-port + cross-encoder adapter; retrieve
topK≈20–30, rerank to 5.

**G2 — Fixed-window chunking / no parent-document retrieval.**
Ingestion stores chunks as parsed; retrieval returns those chunks directly. No parent/child or
contextual chunking, so retrieved chunks can split mid-section and lose surrounding context.

**G3 — No eval harness.**
There is no measured retrieval-quality gate (precision@k / faithfulness) in the repo, so G1/G2
improvements can't be proven. Add an eval harness first (so reranker gains are measurable), per the
earlier recommendation.

## code-memory

**G4 — Hash embeddings, not semantic (by design, but limits conceptual search).**
`HashEmbeddingProvider` is deterministic bag-of-tokens hashing — no model. Empirically re-confirmed
this session: a search for `clampTopK` / `embedding` could not rank the right symbol conceptually.
Fine for exact-token recall; weak for NL intent. (This is why context-mcp keeps lexical `search` and
federates NL to rag-app.) Not a bug; a known ceiling.

**G5 — Name-based call-edge resolution over-matches.**
Edge resolution matches `regexp_replace(symbol_path,'^.*\\.','') = dst_name` within a repo — any
same-named method across classes resolves as a caller. Acceptable for v1 impact hints; imprecise for
overloads / same-name methods.

**G6 — OPERATIONAL (live): cmself-app runs a STALE image.**
`cmself-app` (:8082) runs image `06c9bae3`, which predates the repo-scoping fix. Verified: `--repo`
scoping is a no-op there (returns cross-repo results), whereas `cmrag-app` (:8080, current image
`d09f1f41`) scopes correctly (rag-app→rag-app only, context-mcp→context-mcp only, nonexistent→0).
The source fix is good; only the running cmself-app container is behind. **Recommendation: redeploy
cmself-app to the current image.** Not done automatically — cmself-app is a running app and the
instruction was to keep running apps up; this needs an explicit OK to restart it.

**Also noted:** the shared `cmrag-pg` holds `rag-app` (506) + `context-mcp` (375). Scoping keeps
queries clean on the current image, so coexistence is safe — but a stale image (G6) loses that
safety. Keep all code-memory instances on the scoping-capable image.

## Priority

1. **G6** redeploy cmself-app (operational, low-risk once approved) — restores repo isolation.
2. **G3 → G1** eval harness, then reranker in rag-app (biggest retrieval-quality win).
3. **G2** parent-document chunking in rag-app.
4. **G5** qualifier-aware edge resolution in code-memory (optional; v1-acceptable).
5. **G4** real embeddings in code-memory — only if conceptual code search is wanted there rather
   than federated to rag-app.
