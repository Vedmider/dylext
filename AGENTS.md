# AGENTS.md — start here (any AI)

This is the single entry point for an AI agent working in **Dylext**. It is AI-agnostic:
Claude, Cursor, Kiro, Codex, Aider, or anything that can read a repo. Read this file, wire yourself
in, and follow the usage policy it points at. Everything below links to a single source of truth —
nothing here is a second copy of the policy.

## What this app is (30 seconds)

Dylext is an **MCP server that serves token-cheap code context**: compressed skeletons
(names + signatures) on search/outline, a single symbol's body only on demand via `expand`, plus
live git state (`working_changes`, `recent_changes`) and blast-radius (`impact_of`). The point is to
stop an agent from burning thousands of tokens on `grep` + reading whole files to rediscover a
codebase.

Dylext does one thing well: **exact, structural code context**. It is deliberately **not** the
semantic layer and does **not** call out to other services. The semantic work — concept/prose
answers, fuzzy recall, blending code with documentation — lives at the **AI/agent tier** (see
*Recommended companion* below). Dylext keeps one answer-side guard: **`verify_claims(answer, repo)`**
fact-checks an answer's code references against the live index — each cited symbol is marked verified
(real, with its signature/location) or unverified (not in the index), so hallucinated code is caught
before you trust it.

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
    "Dylext": {
      "command": "bash",
      "args": ["scripts/dev-local.sh", "mcp"],
      "env": { "DB_URL": "jdbc:postgresql://localhost:5432/dylext" }
    }
  }
}
```

### 3. Index this repo once, then verify

```
index(repo="Dylext", root="<abs path to this repo>")
```

Verify the wiring with one cheap call — if this returns a skeleton, you're connected:

```
outline(repo="Dylext", path="src/main/java/io/github/vedmider/dylext/adapter/in/mcp/McpDispatcher.java")
```

## The usage policy (READ THIS — it's the whole point)

The soft policy — which tool answers which question, progressive disclosure, token discipline — is
authored once in:

- **[`docs/AI-USAGE.md`](docs/AI-USAGE.md)** — tool-routing table, `search → digest → expand` flow,
  worked example. **This is the policy. Load it and follow it.**
- **[`docs/SKILL.md`](docs/SKILL.md)** — session memory (two-layer: stream facts, batch decisions
  with *why*) + capture discipline.
- **[`docs/ARCHITECTURE-THREE-APPS.md`](docs/ARCHITECTURE-THREE-APPS.md)** — how the three apps compose.

One-line version: **exact where / show / blast-radius / live edits → Dylext (`search_symbols`,
`outline`, `get_digest`, `expand`, `impact_of`, `working_changes`); concept / why / prose → Sestacon
(the agent calls `/api/retrieve` for chunks or `/api/chat` for a grounded answer); verify code
citations → Dylext `verify_claims`.** The **agent is the semantic layer** that blends the two. Use
`grep`/read-whole-file only when the target isn't indexed code (arbitrary text, logs, config) —
otherwise you're spending tokens this server exists to save.

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

## Recommended companion: Sestacon (the prose/semantic half)

Dylext does not call out to other services. The what/why/which layer lives in **Sestacon**, a
separate RAG app over your own prose (design notes, runbooks, skills, conventions) with source
attribution and ACLs. The **AI agent is the orchestrator**: it pulls exact structure from Dylext,
pulls concepts/prose from Sestacon, blends them in its own reasoning, and grounds any code it cites
with Dylext `verify_claims`.

Two ways the agent uses Sestacon (both reachable through the gateway on `:18070`, or Sestacon
directly on `:18080`):

- `POST /api/retrieve` — returns ranked chunks; the agent reasons, blends and verifies itself
  (cheapest, most control).
- `POST /api/chat` — Sestacon synthesizes a grounded answer with sources via its own LLM.

**Boundary rule:** `/api/chat` is **prose-grounded only** — Sestacon has no code index and cannot
verify code claims. Route code-bearing questions through the agent + Dylext `verify_claims`; use
`/api/chat` for documentation/concept questions.

**Get it:** https://github.com/Vedmider/sestacon (clone it, read *its* `AGENTS.md`, start it).
Running it is optional — without it the agent simply has no prose layer and falls back to code-only
context.
