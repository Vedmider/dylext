# Hard enforcement (opt-in) — Claude Code

The usage policy in [`../../AGENTS.md`](../../AGENTS.md) is advisory: an autonomous agent *should*
prefer context-mcp's tools over native `grep`, but under context pressure it may not. If you observe
that, this adds a **speed bump** — not a cage.

## What it does

Registers a Claude Code **`PreToolUse`** hook on the native **`Grep`** and **`Glob`** tools. Before
either runs, the hook returns a `permissionDecision: "ask"` — Claude pauses and asks you to confirm,
with a reason pointing at the cheaper context-mcp tools (`recall`, `search_symbols`, `outline`).

- It **asks**, it does not **block**. Legitimate searches (logs, config, non-indexed text) proceed
  the moment you confirm. This is deliberate — a hard block trains the agent to route around it
  (e.g. shelling out via `Bash cat`), and breaks the cases where `grep` is genuinely right.
- It touches only `Grep`/`Glob`. It never reads your code or sends anything anywhere.
- It is Claude-specific: hook schemas and tool names aren't portable across AI clients. Every other
  client still gets the soft policy via `AGENTS.md` and the pointer files.

## Install

Merge `hooks/claude/settings.json` into your Claude Code settings
(`.claude/settings.json` in this project, or your user settings). If you have no hooks yet, you can
copy it directly:

```bash
mkdir -p .claude
cp hooks/claude/settings.json .claude/settings.json
```

If you already have a `.claude/settings.json`, merge the `hooks.PreToolUse` array by hand rather than
overwriting.

## Remove

Delete the `PreToolUse` entry you added (or remove `.claude/settings.json` if the hook was its only
content). Nothing else is left behind; the script in this folder is inert unless referenced by
settings.
