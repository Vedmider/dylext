#!/bin/bash
# Installs the Dylext agents into ~/.kiro/agents with this repo's absolute path and validates them.
#   1) dylext-sestacon-tester  - QA / gap-finding / self-diagnostic agent
#   2) dylext-sestacon-pro      - AI-agnostic production agent that works via the app APIs (token-cheap)
set -eu
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
DEST_DIR="${KIRO_AGENTS_DIR:-$HOME/.kiro/agents}"
mkdir -p "$DEST_DIR"
chmod +x "$HERE"/tester/*.sh "$HERE"/tester/dx

for agent in dylext-sestacon-tester dylext-sestacon-pro; do
  DEST="$DEST_DIR/$agent.json"
  [ -f "$DEST" ] && cp "$DEST" "$DEST.bak"
  sed "s#__REPO__#$REPO#g" "$HERE/$agent.json" > "$DEST"
  echo "installed $DEST"
  kiro-cli agent validate --path "$DEST"
done
