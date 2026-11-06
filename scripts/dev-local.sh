#!/bin/bash
# Local dev loop for context-mcp. Brings up pgvector + the app as containers, checks health,
# and can run the token-savings harness. All data is ephemeral.
#
#   bash scripts/dev-local.sh up        # build image (via mirror) + compose up + wait health
#   bash scripts/dev-local.sh test      # run the A/B token-savings harness (mvn IT, real fixture)
#   bash scripts/dev-local.sh mcp       # run the jar in stdio MCP mode (for an MCP client)
#   bash scripts/dev-local.sh down      # tear down + wipe data
#   bash scripts/dev-local.sh all       # up -> health -> down
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIRROR="${MIRROR:-armdocker.rnd.ericsson.se/dockerhub-ericsson-remote}"
export REGISTRY="${REGISTRY:-$MIRROR/library}"
export PGVECTOR_IMAGE="${PGVECTOR_IMAGE:-$MIRROR/pgvector/pgvector:pg16}"
export APP_IMAGE="${APP_IMAGE:-context-mcp:0.1.0}"
export APP_PORT="${APP_PORT:-8080}"
BASE="http://localhost:${APP_PORT}"

build_image() {
  local secret=()
  [ -f "$HOME/.m2/settings.xml" ] && secret=(--secret id=m2settings,src="$HOME/.m2/settings.xml")
  echo "== build $APP_IMAGE (registry: $REGISTRY) =="
  ( cd "$REPO" && DOCKER_BUILDKIT=1 docker build --build-arg REGISTRY="$REGISTRY" \
      "${secret[@]}" -t "$APP_IMAGE" . )
}

up() {
  build_image
  echo "== compose up =="
  ( cd "$REPO" && docker compose up -d )
  echo "== waiting for app health =="
  for _ in $(seq 1 60); do
    [ "$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "$BASE/actuator/health" 2>/dev/null)" = "200" ] \
      && { echo "UP -> $BASE"; return 0; }
    sleep 3
  done
  echo "app did not become healthy:"; ( cd "$REPO" && docker compose logs app | tail -40 ); exit 1
}

down() {
  echo "== tearing down + wiping data =="
  ( cd "$REPO" && docker compose down -v --remove-orphans )
}

test_harness() {
  echo "== A/B token-savings harness (mvn IT against real fixture) =="
  ( cd "$REPO" && source env.sh && mvn -B test-compile failsafe:integration-test \
      -Dit.test=HarnessServiceIT )
  echo "See docs/EVIDENCE.md for the measured savings table."
}

mcp_stdio() {
  local jar="$REPO/target/context-mcp-0.1.0.jar"
  [ -f "$jar" ] || ( cd "$REPO" && source env.sh && mvn -B -q -DskipTests package )
  echo "== context-mcp stdio MCP server (Ctrl-D to quit) =="
  java -Dcontextmcp.mcp.stdio=true \
       -DDB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/contextmcp}" \
       -jar "$jar"
}

case "${1:-}" in
  up)      up ;;
  down)    down ;;
  rebuild) down 2>/dev/null; up ;;
  test)    test_harness ;;
  mcp)     mcp_stdio ;;
  all)     trap 'echo "=== DOWN ==="; down' EXIT; up ;;
  *) echo "usage: $0 {up|down|rebuild|test|mcp|all}"; exit 2 ;;
esac
