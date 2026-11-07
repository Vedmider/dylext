# AGENTS.md — start here (any AI)

This is the single entry point for an AI agent working in **context-mcp**. It is AI-agnostic:
Claude, Cursor, Kiro, Codex, Aider, or anything that can read a repo. Read this file, wire yourself
in, and follow the usage policy it points at. Everything below links to a single source of truth —
nothing here is a second copy of the policy.

## What this app is (30 seconds)

context-mcp is an **MCP server that serves token-cheap code context**: compressed skeletons
(names + signatures) on search/outline, a single symbol's body only on demand via `expand`, plus
live git state (`working_changes`, `recent_changes`) and blast-radius (`impact_of`). The point is to
stop an agent from burning thousands of tokens on `grep` + reading whole files to rediscover a
codebase.

It is the **single endpoint**: it also federates `explain` (concept/doc answers) and `recall`
(fuzzy code recall) so you talk to one server.

## How to wire your AI in

### 1. Run the server

```bash
bash scripts/dev-local.sh up      # pgvector + app as containers, waits for health
bash scripts/dev-local.sh mcp     # run as a stdio MCP server for your MCP client
```

### 2. Point your MCP client at it

Any MCP-capable client. Paste a server entry like this into your client's MCP config
(the exact file differs per client — Claude: `.mcp.json` / `claude_desktop_config.json`;
Cursor: `.cursor/mcp.json`; Kiro: `.kiro/settings/mcp.json`):

```json
{
  "mcpServers": {
    "context-mcp": {
      "command": "bash",
      "args": ["scripts/dev-local.sh", "mcp"],
      "env": { "DB_URL": "jdbc:postgresql://localhost:5432/contextmcp" }
    }
  }
}
```

### 3. Index this repo once, then verify

```
index(repo="context-mcp", root="<abs path to this repo>")
```

Verify the wiring with one cheap call — if this returns a skeleton, you're connected:

```
outline(repo="context-mcp", path="src/main/java/com/example/contextmcp/adapter/in/mcp/McpDispatcher.java")
```

## The usage policy (READ THIS — it's the whole point)

The soft policy — which tool answers which question, progressive disclosure, token discipline — is
authored once in:

- **[`docs/AI-USAGE.md`](docs/AI-USAGE.md)** — tool-routing table, `search → digest → expand` flow,
  worked example. **This is the policy. Load it and follow it.**
- **[`docs/SKILL.md`](docs/SKILL.md)** — session memory (two-layer: stream facts, batch decisions
  with *why*) + capture discipline.
- **[`docs/ARCHITECTURE-THREE-APPS.md`](docs/ARCHITECTURE-THREE-APPS.md)** — how the three apps compose.

One-line version: **concept → `explain`; fuzzy where (name unknown) → `recall`; exact where / show /
live edits → native tools.** Use `grep`/read-whole-file only when the target isn't indexed code
(arbitrary text, logs, config) — otherwise you're spending tokens this server exists to save.

## Two ways to adopt the policy

You choose how strongly to bind the AI to it:

- **Soft (default, agnostic)** — the policy is delivered as a skill/steering file your client
  auto-loads. Thin per-client pointers already exist (`CLAUDE.md`, `.cursor/rules/usage.md`,
  `.kiro/steering/usage.md`) and all point back here. No setup; the AI *should* follow it. An
  autonomous agent can still ignore it under pressure.
- **Hard (opt-in, Claude Code)** — if you observe the AI still reaching for `grep`/raw reads on
  indexed code, install a `PreToolUse` speed-bump that makes it **confirm** before doing so (it
  asks, it does not hard-block — legitimate grep still works). See
  **[`hooks/claude/install.md`](hooks/claude/install.md)**. Claude-specific by nature (hook schemas
  aren't portable); the soft layer covers every other client.

## Recommended companion: rag-app (extended functionality)

context-mcp's `explain` tool federates to **rag-app** for concept/documentation answers grounded in
your own prose (design notes, runbooks, skills, conventions) with source attribution. Running
rag-app alongside context-mcp gives the AI the full "what/why/which" layer on top of exact code —
without it, `explain` degrades gracefully (returns empty, you fall back to local lexical search).

**Recommended for extended functionality:** https://github.com/Vedmider/rag-app
(clone it, read *its* `AGENTS.md`, start it; context-mcp will federate to it automatically.)
