# Token-savings evidence — context-mcp

The product metric is **net tokens saved per task**. We prove it with an A/B harness that runs a
fixed set of real tasks two ways over the same indexed repo:

- **WITH-MCP** — `search_symbols` → the digests the agent sees, drilling into a raw span with
  `expand` only when the task needs the body.
- **WITHOUT** — read, whole, every file that holds a candidate symbol (the naive baseline).

Both sides are tokenized with the *same* tokenizer so the comparison is apples-to-apples.

## Measured (fixture = context-mcp's own `src/main/java`)

| task | hits | files | WITH-MCP | WITHOUT | saved |
|---|---:|---:|---:|---:|---:|
| find retrieval service | 4 | 4 | 86 | 3878 | +98% |
| locate token ledger | 2 | 2 | 51 | 414 | +88% |
| git working changes | 4 | 4 | 58 | 2041 | +97% |
| **total** |  |  | **195** | **6333** | **+97%** |

Numbers above use the in-JVM `EstimatingTokenizer`. The savings are large because the realistic
agent workflow is *finding scattered code*: the baseline reads several whole files to locate a few
symbols, while digests return just the signatures and `expand` returns just one span.

## Reproduce

- Headless (CI): `mvn -B verify` runs `HarnessServiceIT`, which asserts the comparison is populated
  and that WITH-MCP costs strictly fewer tokens than reading whole files in aggregate.
- External, real tokenizer (tiktoken `cl100k_base`):
  ```bash
  mvn -q -DskipTests package
  ~/.kiro/crew-venv/bin/python3.12 scripts/ab-harness.py --repo self --root src/main/java
  ```

## Honest caveats

- Savings are largest on scattered-symbol tasks (the common agent case). A task that truly needs a
  whole large file read end-to-end will save little — `expand` returns one symbol span, not a file.
- The summary **build** cost (when a real summarizer is enabled) is recorded to the ledger as a
  cost (`summarize:build`), so net savings stay honest once summarization is turned on.
