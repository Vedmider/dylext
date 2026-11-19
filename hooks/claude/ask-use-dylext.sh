#!/usr/bin/env bash
# Claude Code PreToolUse hook (opt-in). Fires before a native Grep/Glob.
# It does NOT block — it asks the agent to confirm, nudging it toward Dylext's
# token-cheap tools (recall / search_symbols / outline) for indexed code. Legitimate
# searches (non-code text, logs, config, unindexed repos) proceed on confirm.
#
# Claude passes the tool call as JSON on stdin; we only need to emit a decision on stdout.
# See https://docs.anthropic.com/en/docs/claude-code/hooks for the contract.

cat <<'JSON'
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "ask",
    "permissionDecisionReason": "This repo is served by Dylext. For indexed code prefer recall(query) (fuzzy, name unknown) or search_symbols/outline (exact name) + get_digest/expand — far cheaper than grep. Confirm only if you're searching non-indexed content (logs, config, arbitrary text). See AGENTS.md."
  }
}
JSON
exit 0
