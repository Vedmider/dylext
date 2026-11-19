#!/bin/bash
# Dylext server for the tester agent (HTTP JSON-RPC at /mcp): up | down | status | restart | logs
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# Optional LOCAL, uncommitted overrides (e.g. an internal registry mirror). See .local.env.example.
[ -f "$REPO/.local.env" ] && . "$REPO/.local.env"
MIRROR="${MIRROR:-docker.io}"
PG=dylext-agent-pg
PG_PORT="${PG_PORT:-55434}"
PORT="${DX_PORT:-18090}"
# Multi-language mode: point the app at the parser sidecars (started by parsers.sh).
PY_PARSER_URL="${PY_PARSER_URL:-http://localhost:${PY_PARSER_PORT:-9091}}"
GO_PARSER_URL="${GO_PARSER_URL:-http://localhost:${GO_PARSER_PORT:-9092}}"
TS_PARSER_URL="${TS_PARSER_URL:-http://localhost:${TS_PARSER_PORT:-9093}}"
STATE=/tmp/tester-dylext
PIDF="$STATE/app.pid"
LOG="$STATE/app.log"
JAR="$REPO/target/dylext-0.2.0.jar"
mkdir -p "$STATE"

running() { [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; }

healthy() { [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://localhost:$PORT/actuator/health")" = "200" ]; }

pg_up() {
  docker ps --format '{{.Names}}' | grep -qx "$PG" && return 0
  docker rm -f "$PG" >/dev/null 2>&1
  docker run -d --name "$PG" -p "$PG_PORT:5432" -e POSTGRES_DB=dylext -e POSTGRES_USER=dylext \
    -e POSTGRES_PASSWORD=dylext "${PGVECTOR_IMAGE:-$MIRROR/pgvector/pgvector:pg16}" >/dev/null || return 1
  for _ in $(seq 1 40); do docker exec "$PG" pg_isready -U dylext >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

up() {
  # If a healthy server is already running, reuse it — UNLESS the jar was rebuilt since it launched
  # (PIDF mtime ≈ launch time). `up` alone would otherwise serve a stale binary after a `package`.
  if running && healthy; then
    if [ -f "$JAR" ] && [ "$JAR" -nt "$PIDF" ]; then
      echo "jar is newer than the running server — restarting to pick up the rebuild"
      down --keep-db
    else
      echo "UP http://localhost:$PORT/mcp (pid $(cat "$PIDF"))"; return 0
    fi
  fi
  source "$REPO/env.sh"
  pg_up || { echo "postgres did not start"; exit 1; }
  [ -f "$JAR" ] || ( cd "$REPO" && mvn -B -q -DskipTests package ) || { echo "build failed"; exit 1; }
  nohup java -Dserver.port="$PORT" -DDB_URL="jdbc:postgresql://localhost:$PG_PORT/dylext" \
    -Ddylext.parsers.python="$PY_PARSER_URL" -Ddylext.parsers.go="$GO_PARSER_URL" \
    -Ddylext.parsers.typescript="$TS_PARSER_URL" \
    -jar "$JAR" >"$LOG" 2>&1 &
  echo $! > "$PIDF"
  for _ in $(seq 1 60); do healthy && { echo "UP http://localhost:$PORT/mcp (pid $(cat "$PIDF"))"; return 0; }; sleep 1; done
  echo "dylext did not become healthy; tail of $LOG:"; tail -20 "$LOG"; exit 1
}

down() {
  running && kill "$(cat "$PIDF")" 2>/dev/null
  rm -f "$PIDF"
  [ "${1:-}" = "--keep-db" ] || docker rm -f "$PG" >/dev/null 2>&1
  echo "down"
}

case "${1:-}" in
  up) up ;;
  down) shift; down "$@" ;;
  restart) down --keep-db; up ;;
  status) running && healthy && echo "UP pid $(cat "$PIDF")" || echo "DOWN" ;;
  logs) tail -n "${2:-60}" "$LOG" ;;
  *) echo "usage: $0 {up|down [--keep-db]|restart|status|logs [n]}"; exit 2 ;;
esac
