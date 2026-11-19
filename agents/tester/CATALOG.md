# Tester catalog

Status: `todo` | `done-pass` | `done-finding(F-nnn)`. Add new scenarios at the bottom of their section before running them.
Dylext developer-activity cases (O/B/G/F/M/T/S) already live in `e2e/dev_activity.py`; this catalog lists what is
NOT covered there. Sestacon API cases live in `e2e/sestacon_probe.py` (SA01-SA13).

## Dylext, uncovered or exploratory
| ID | Scenario | Status |
|---|---|---|
| DX01 | `search_symbols` with regex/SQL metacharacters, `%`, `_`, quotes, very long query | todo |
| DX02 | `pathPrefix` with `..`, absolute path, backslashes, unicode | todo |
| DX03 | `expand`/`get_digest` for a symbol whose file was deleted on disk but not yet re-indexed | todo |
| DX04 | `index` of a huge single file (5 MB generated source) and a 20k-file repo: time and memory | todo |
| DX05 | symlinked directory and symlink loop inside a repo | todo |
| DX06 | git submodule: root listing, `working_changes`, index behaviour | todo |
| DX07 | two MCP clients indexing and querying the same repo concurrently | todo |
| DX08 | restart Dylext mid-index, then resume: no duplicate or half-written symbols | todo |
| DX09 | DB down during a query: error shape and recovery without restart | todo |
| DX10 | MCP protocol: invalid JSON, unknown method, missing `params`, huge payload, id types | todo |
| DX11 | `session_*` with 10k facts, unicode, empty `what`, same id from two callers | todo |
| DX12 | `verify_claims` precision: homonyms, overloaded methods, generic types, similar names across modules | done(I-003 C+A) |
| DX13 | `impact_of` on overloads, interfaces, lambdas, static imports, reflection-only callers | done(I-003 C+A) |
| DX14 | `recent_changes` / `working_changes` on a repo with 100k commits, binary diffs, CRLF-only changes | todo |
| DX15 | non-Java repos: Go, Python, TypeScript with and without sidecar parsers; `languages` honesty | done-finding(F-009,F-010) |
| DX16 | `ledger` totals after a mixed session match the sum of served spans | done-finding(F-006,F-007) |
| DX17 | repo with path containing spaces, unicode, or very deep nesting | todo |
| DX18 | `git gc`, shallow-to-deep fetch, force-push upstream, orphan branch checkout | todo |
| DX19 | `answer` intent classification on ambiguous questions | todo |
| DX20 | tools after the repo directory is moved or deleted | todo |
| DX21 | stdio stdout purity when launched with defaults | done-finding(F-001) |
| DX22 | HTTP `/mcp`: notifications, batch arrays, wrong content type, huge body, concurrent calls | todo |
| DX23 | `impact_of` for framework-wired classes (Spring beans, controllers) reports zero callers: is that signalled? | done-finding(F-005) |
| DX24 | TS/JS idiomatic parsing: arrow-fn/function-expr consts, CommonJS `module.exports`/`exports.x`, `type` aliases, anonymous `export default`; call edges from arrow consts | done-finding(F-019) |

## Sestacon, uncovered or exploratory
| ID | Scenario | Status |
|---|---|---|
| SX01 | ingest each allowed content type with real files (md, txt, pdf, docx, html, csv, json) | done-pass |
| SX02 | file at the size limit, one byte over, and a zip/archive | done-pass |
| SX03 | filename with path separators, `..`, unicode, very long, duplicate in one request | done-pass |
| SX04 | chunk boundaries: a fact split across chunks; very long single line; no whitespace | done-finding(F-013) |
| SX05 | roles: multiple roles, case sensitivity, empty `X-Roles`, role not matching, aclTags on re-ingest | done-pass |
| SX06 | metadata filter combos: unknown key, wrong type, nested, filter + ACL | done-finding(F-011) |
| SX07 | ranking: exact phrase vs paraphrase vs keyword; hybrid vs vector (if configurable) | done-finding(F-013) |
| SX08 | 50 parallel ingests and retrievals: errors, duplicates, latency | done-pass |
| SX09 | restart Sestacon: documents still retrievable; cache behaviour | done-pass |
| SX10 | `/api/documents` chunk ingest with bad payloads; delete unknown id | todo |
| SX11 | `/api/chat` with guardrail-triggering input, huge question, no matching docs | done-finding(F-012) |
| SX12 | prompt-injection text inside an ingested doc must not leak other docs or act as instructions | done-pass |
| SX13 | error response shape consistency across all endpoints | done-pass |
| SX14 | retrieval returns unrelated low-score hits for a topic with no docs (no score floor / no "no match" signal) | done-finding(F-004) |

## Cross-system
| ID | Scenario | Status |
|---|---|---|
| XS01 | Dylext `explain` with Sestacon up/down/slow: status and latency bound | done-finding(F-004) |
| XS02 | roles passed through Dylext reach Sestacon; restricted doc not in `answer` for anon | todo |
| XS03 | `answer` citations all pass `verify_claims` | done-finding(F-003) |
| XS04 | ingest design docs about Dylext into Sestacon, then ask Dylext `explain` about itself: relevant and correct | todo |

## Agent tooling gaps
Record every FALLBACK from `TOOL-LOG.md` here as `AT-nnn` with the missing capability.
