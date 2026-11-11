# Token-savings evidence — Dylext

The product metric is **net tokens saved per task**. We prove it with an A/B harness that runs a
fixed set of real tasks two ways over the same indexed repo:

- **WITH-MCP** — `search_symbols` → the digests the agent sees, drilling into a raw span with
  `expand` only when the task needs the body.
- **WITHOUT** — read, whole, every file that holds a candidate symbol (the naive baseline).

Both sides are tokenized with the *same* tokenizer so the comparison is apples-to-apples.

## Measured (fixture = Dylext's own `src/main/java`)

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


## Parser quality — polyglot fidelity (Tiers 1–2)

Measured via `~/e2e/dylext_metrics.py` (MCP index → sampled symbols → `impact_of`) on real repos,
before vs after the Tier-1/2 uplift. "Before" = skeleton-level (names + bare signatures); "after" =
typed signatures + structural kinds + qualified, cross-file-resolvable call edges.

| repo (files) | symbols before → after | kinds | signature completeness | edge resolution* |
|---|---|---|---|---|
| Go — `eric-ran-rad-lcm-batchservice` (332) | 4270 → **6222** (+46%) | 2 → **4** (CLASS, FIELD, INTERFACE, METHOD) | 66% of methods carry params + return | 62% of sampled methods have ≥1 resolved caller |
| Python — `cloudran-test-rce` (181) | 1058 → **3755** (+255%) | **3** (CLASS, FIELD, METHOD) | **100%** typed | 28%* |

\* Edge resolution = fraction of sampled methods for which `impact_of` returns at least one caller
resolved to a defining symbol **in the same repo**. Python is lower by nature: many calls target
imported/builtin/dynamic names with no in-repo definition — a true property of the codebase, not a
parser defect. The symbol-count jumps come from newly captured structural symbols (Go struct
fields / interface methods / consts; Python module vars / class attributes).

Quality is a measured number, not a claim; re-run the gate after any parser change to catch
regressions. All parsers conform to `parsers/CONTRACT.md` (shared fixture enforced in each sidecar's
tests).
