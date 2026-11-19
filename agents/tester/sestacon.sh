#!/bin/bash
# Sestacon lifecycle for the tester agent: up | down | status | logs | rebuild | probe [args]
SES=/local/IdeaProjects/sestacon
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "${1:-}" in
  up|down|status|logs|rebuild) bash "$SES/scripts/dev-local.sh" "$1" ;;
  probe) shift; python3 "$HERE/../../e2e/sestacon_probe.py" "$@" ;;
  *) echo "usage: $0 {up|down|status|logs|rebuild|probe [--only SA02]}"; exit 2 ;;
esac
