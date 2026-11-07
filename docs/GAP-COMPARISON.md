# Gap comparison: Dylext vs code-memory

Both servers indexed the **same fixture** — `Sestacon/src/main/java` — and were asked the **same
three locating tasks**. code-memory ran live via `apicall.py` against its existing Sestacon index
(506 symbols); Dylext indexed the same tree headlessly (55 files, 402 symbols) and answered
through its own `search` / `expand`.

This is a design comparison on the same stack (Java/Spring + Postgres/pgvector), so differences are
architectural, not language/runtime artifacts.

## Same tasks, top hit each

| task | code-memory top hit (span) | Dylext top hit (span) |
|---|---|---|
| `RetrieveController` | `RetrieveController` class — 47 lines | `RetrieveController` class — 47 lines |
| `OpenAiEmbeddingProvider` | `DocumentChunk.of` — 3 lines (**wrong symbol first**) | `openAiEmbeddingProvider` method — 13 lines |
| `effectiveTopK` | `RetrieveController.retrieve` — 21 lines (**method containing it, not it**) | `effectiveTopK` — 6 lines (**exact**) |

## Findings

**1. Retrieval precision at the symbol level.**
For `effectiveTopK`, code-memory's best hit was the *enclosing* method `retrieve` (21 lines); it did
not surface the `effectiveTopK` method itself at the top. Dylext returned `effectiveTopK`
exactly (6-line span, 208 chars). code-memory's hybrid vector+text ranking (hash embeddings) ranked
a broader symbol first; Dylext's lexical name match is exact for known identifiers. For the
"jump to this named thing" workflow, exact-name matching wins; for conceptual/NL queries,
code-memory's embeddings would win (Dylext has no embeddings — by design, that belongs in a
doc-RAG, not the code-context server).

**2. Expand granularity / the whole-file-span problem.**
Dylext's `expand` always returns the *symbol's* span: 6 lines / 208 chars for `effectiveTopK`,
13 lines / 737 chars for the provider method. This is the progressive-disclosure contract — the
agent pays for exactly the symbol it drilled into. code-memory returns stored symbol spans too, but
its earlier measured failure mode was `<module>`-level (whole-file) spans dominating on large files
(the −19% token case in its own EVIDENCE). Dylext avoids that by construction: `<module>` is a
skeleton (signatures only), and bodies are only ever reached per-symbol via `expand`.

**3. Uncommitted-change context — present vs absent.**
Dylext has `working_changes` (uncommitted working-tree diff vs HEAD) as a first-class tool.
code-memory has no equivalent — it serves committed/indexed state only. For an agent editing code
*right now*, this is the difference between seeing the live edit and not. This was called out as the
expected gap in the plan and is confirmed.

**4. Token accounting is built in vs bolted on.**
Dylext records served-vs-baseline to a ledger on every tool call and ships an A/B harness
(`+97%` measured) as part of the server. code-memory's token proof is an external script
(`context-ledger.sh`) computed after the fact. Same honesty of numbers; Dylext makes the metric
a first-class, queryable server feature (`ledger` tool / `tokens` footer on every response).

**5. Progressive-disclosure handle.**
Dylext returns symbol IDs from `search`/`outline` that the agent expands on demand
(outline → digest → expand). code-memory returns richer per-hit payloads up front (callers/tests in
`context`), which is more tokens per call but fewer round-trips. Tradeoff, not a strict win: Dylext
spends fewer tokens when the agent only needs a couple of symbols; code-memory spends fewer
round-trips when it needs the whole neighbourhood.

## Where code-memory is ahead

- **Conceptual/NL search** via real hybrid retrieval (vector + full-text + RRF + PageRank/PPR). Dylext
  is deliberately lexical/structural only; NL search is the doc-RAG's job.
- **Graph ranking** (global PageRank + personalized PPR) biases results toward well-connected code;
  Dylext has the call graph (`impact_of`) but does not rank search by it yet.
- **Maturity**: migrations consolidated, Helm chart, multi-language chunking service, git-sync hooks.

## Where Dylext is ahead

- **Symbol-exact expand** with no whole-file-span regression (progressive disclosure by construction).
- **Uncommitted working-tree context** (`working_changes`) — absent in code-memory.
- **Built-in token ledger + A/B harness** as server features, not external scripts.
- **Compression-first skeletons** (names+signatures) as the default served unit; bodies are opt-in.

## Net

The two make opposite central bets. code-memory optimises **recall across a codebase** (embeddings +
graph) and is the better "help me understand this unfamiliar area" server. Dylext optimises
**tokens per known-target lookup** (exact symbol → minimal span) and adds **live edit awareness** +
**first-class savings accounting** — the better "cut my per-task token cost while I actively edit"
server. The strongest system would combine them: Dylext's symbol-exact expand, working-tree
context, and ledger, plus code-memory's embeddings + graph ranking for the conceptual-recall path.
