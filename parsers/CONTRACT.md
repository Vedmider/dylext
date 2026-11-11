# Neutral parser contract

The single source of truth shared by every parser (in-process `JavaSkeletonExtractor` and the
out-of-process `parsers/python`, `parsers/go` sidecars) and the `HttpSkeletonExtractor` adapter.
A parser's only job: turn one source file into a list of **neutral symbols**. Dylext owns identity
(`repo:filePath:symbolPath`), content hashing, storage, edges and git — parsers never do.

## Endpoint (sidecars)
```
POST /parse   {repo, filePath, language, source}  ->  {symbols: [Symbol], error?}
GET  /health  ->  {status: "UP"}
```
On parse failure a sidecar returns `{"symbols": []}`; Dylext then synthesizes a single MODULE
symbol so indexing never fails.

## Symbol
```jsonc
{
  "kind":         "MODULE|CLASS|INTERFACE|ENUM|RECORD|METHOD|CONSTRUCTOR|FIELD", // required
  "name":         "string",            // required — short name
  "symbolPath":   "string",            // required — Parent#member / Parent.Nested; "<module>" for MODULE
  "signature":    "string",            // required — full: params (name+type), returns, modifiers/decorators
  "docFirstLine": "string",            // optional — first meaningful doc/comment line ("")
  "startLine":    1,                   // optional — 1-based (default 1)
  "endLine":      1,                   // optional — 1-based (default startLine)
  "callTargets":  ["string"],          // optional — QUALIFIED where known (recv.method, pkg.Func, self.m, alias.f)

  // ---- Tier 1 enrichment (all OPTIONAL; omit when unknown) ----
  "fqName":       "string",            // fully-qualified name for cross-file resolution (pkg.Type#method, module.Class.method)
  "receiver":     "string",            // owner/receiver type (Go method recv, Python class, Java enclosing type)
  "returnType":   "string",            // declared/annotated return type
  "modifiers":    ["string"],          // exported/public/static/async + decorator names
  "imports":      ["string"]           // MODULE symbol only — imported names/paths/aliases (dependency surface)
}
```

### Rules
- `kind` MUST be one of Dylext's `SymbolKind` names (above). Unknown → adapter defaults to METHOD.
- `symbolPath` MUST be unique within the file; Dylext forms `id = repo:filePath:symbolPath`.
- Exactly zero or one MODULE symbol per file. If the parser omits it, Dylext synthesizes one.
- New Tier-1 fields are additive; older sidecars that omit them stay valid (adapter is default-tolerant).
- `callTargets` carry **qualified** names where the parser can resolve them intra-file; bare names
  otherwise. Dylext's `resolvePendingEdges` links them to defining symbols across files.

## Per-language mapping
| concept            | Java (in-proc)     | Go (sidecar)              | Python (sidecar)            |
|--------------------|--------------------|---------------------------|-----------------------------|
| type/class         | CLASS              | struct → CLASS            | class → CLASS               |
| interface          | INTERFACE          | interface → INTERFACE     | (ABC/Protocol) → CLASS      |
| enum / record      | ENUM / RECORD      | —                         | —                           |
| function/method    | METHOD             | func → METHOD             | def → METHOD                |
| constructor        | CONSTRUCTOR        | —                         | `__init__` → CONSTRUCTOR     |
| field/const/var    | FIELD              | field/const/var → FIELD   | class attr / module var → FIELD |
| receiver/owner     | enclosing type     | method recv type          | enclosing class             |
| module imports     | import decls       | import specs (+alias)     | import / from-import (+as)  |

## Conformance fixtures
`parsers/conformance/` holds one fixture per language (`sample.<ext>`) and the expected neutral
symbols (`sample.<ext>.expected.json`). Each sidecar's self-test asserts its `extract()` output
matches the expected set for the shared fixture, guaranteeing all parsers agree on this contract.
