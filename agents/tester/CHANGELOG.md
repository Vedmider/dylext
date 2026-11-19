# Tester agent changelog

- Initial agent, prompt, catalog, Sestacon probe and launchers.
- Native MCP servers are disabled by Kiro enterprise policy (`mcpReason: admin_disabled`), so Dylext is reached via
  `agents/tester/dx` over the new HTTP `POST /mcp` endpoint; server lifecycle in `agents/tester/dylext.sh`.
- Prompt moved inline (absolute/out-of-workspace `file://` prompts are rejected); full instructions loaded as a resource.

- Added HARD-RULE item 6: fallback/verify grep must be EXPLICIT and SCOPED (includePattern or path), never
  system/workspace-wide. Reason: user aborted unscoped grep_search runs; scope keeps verification offline and
  bounded to the target repo. (prompt only; no agent config change, takes effect next session.)

- Tightened HARD-RULE item 6: the `grep_search` tool freezes in this env — forbidden; use bash
  `grep -n <pattern> <explicit path>` or `read_file` on a concrete path. Reason: user hit a freeze twice and
  instructed to drop grep_search. (prompt only; effective next session.)

- Added `agents/tester/parsers.sh` (build+run the repo's Python/Go parser sidecars as tester-* containers,
  docker-run kept in-script per policy) and wired `dylext.sh` to pass `-Ddylext.parsers.python/go`; prompt
  Environment now starts/stops them. Enables Dylext multi-language mode for the tester (F-010). Per-file
  sidecar routing (IndexService.router.extractorFor by extension) confirmed working for java/python/go.

- Rewrote Style: console prints only findings/evidence/decisions/proposals/results; no mechanic narration
  (file-write/stage/commit/"let me…"), no raw logs/JSON/whole files; long reasoning condensed to key points +
  conclusion + proposal. Reason: user asked to cut console noise and summarize long thinking. (prompt only.)

- Style hard rule: NEVER print file contents/diffs/code blocks in the console (forbidden, wastes tokens);
  after edits print only changed-file paths + an end summary. Reason: user forbade file-content echo twice.

- Style: added the real root-cause of console noise — tool I/O volume (file reads, bash output, edit echoes),
  which a prompt cannot suppress. Rule now mandates minimizing tool I/O (narrow offset/limit reads, digest/expand
  over source reads, head-capped bash, no re-reads, batched lookups). Reason: user still saw heavy noise; the
  earlier prose-only rules missed that tool I/O (not prose) dominates, and that tester-prompt.md governs the
  delegate agent, not the main chat session.

- I-001: added TypeScript/JS parser sidecar (parsers/ts, Node + TS Compiler API) honouring the neutral CONTRACT;
  LanguageProperties maps ts/tsx/js/jsx/mjs/cjs -> typescript; wired into parsers.sh (tester-ts-parser:9093),
  dylext.sh, and docker-compose. Enables JS/TS indexing (eric-ran-rad-lcm-ui: 149 unparsed js -> 151 ts files /
  1262 symbols). parsers.sh now starts/stops 3 sidecars.

- I-003: overload/homonym-precise call-edge resolution. V3 migration (symbols.arity, edges.dst_arity/dst_recv);
  CodeEdge carries arity+recv; call targets encode "[recv.]name[/arity]" (Java + TS emit /arity); resolver picks
  arity-exact -> receiver-match -> stable id behind dylext.edges.resolve (default arity). Java overloads now
  resolve by call-site arity end-to-end. Go/Python unchanged (no method overloading). See I-003-DESIGN.md.

- 2026-11-19 dylext.sh runs a copy of the jar (/tmp/tester-dylext-run.jar): rebuilding target/*.jar under the running JVM caused NoClassDefFoundError (logback ThrowableProxy) and gateway timeouts (HARNESS).
