# Tool-use log

`FALLBACK | need | app tool tried | why it failed | built-in used` and `VERIFY | app answer | ground truth used | match?`


FALLBACK | read pom.xml for DOCKER_API_VERSION/failsafe config | outline/search_symbols | pom.xml is XML (unindexed; Dylext parses Java only) | bash `grep -n` on explicit path
FALLBACK | view full editable source of files I was about to change (UnifiedAnswerService, ImpactService, DigestStore, Symbol, SymbolKind, ImpactUseCase, LedgerService + tests) | search_symbols/outline used first for location+signatures; get_digest/expand give a single symbol span, not whole-file edit context | read_file (rule 4: editing is a built-in)
VERIFY | search_symbols says real pkg is io.github.vedmider.dylext | bash grep on AGENTS.md | match — docs' com.example.dylext path is stale (F-008)


FALLBACK | inspect McpHttpController + find filter/security beans | should have used dx get_digest/search_symbols first | used cat + bash grep before switching | re-did via dx search_symbols/get_digest (app-first); Sestacon retrieve on "mcp auth" returned [] (no doc)

FALLBACK | find call sites of recordCost / edge schema / controller bodies for I-003/I-004 edits | dx search_symbols used for entry points, but subsequent call-site/schema/field discovery went straight to bash grep + read_file | edit-prep needs exact verbatim file content (rule 4) and no-glob steering forces bash grep; logged for honesty | bash `grep -rn` scoped to each repo subtree + read_file; results verified against dx search_symbols/list_repos (VERIFY)

VERIFY | sestacon ingest schema/allowlist/parser for an architecture analysis | should have used dylext get_digest/expand/search_symbols first (sestacon IS indexed) | I reached for bash grep/read on sestacon source instead | corrected after "use dylext" steering: re-sourced via dx get_digest(DocumentChunk, DocumentParser), expand(IngestProperties#allowedContentTypes), search_symbols(TikaDocumentParser) — all confirmed the same facts.


VERIFY | existence of named gap-doc components | dx verify_claims(dylext) + verify_claims(sestacon) + search_symbols | confirmed VERIFIED/UNVERIFIED per symbol; ground truth OK
FALLBACK | locate harness files ab-harness.py / sestacon_probe.py by NAME | search_symbols (symbol index, not filenames) | app indexes symbols not arbitrary filenames | scoped ls/find under the two repos
VERIFY | eval package contains CI/bootstrap/percentile/slice/calibration logic | dx outline of AbComparison/EvalHarness/JudgeEvalHarness | confirmed API surface only exposes deltas/means | scoped grep over sestacon/.../application/eval + scripts/ab-harness.py (0 stats hits)
