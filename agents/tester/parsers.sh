#!/bin/bash
# Dylext language-parser sidecars for the tester agent: up | down | status | logs
# Enables multi-language mode (Python, Go) by running the repo's sidecars as tester-* containers.
# docker run lives here (not inline) per the tester policy.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# Optional LOCAL, uncommitted overrides (e.g. an internal registry mirror). See .local.env.example.
[ -f "$REPO/.local.env" ] && . "$REPO/.local.env"
MIRROR="${MIRROR:-docker.io}"

PY_NAME=tester-python-parser
GO_NAME=tester-go-parser
TS_NAME=tester-ts-parser
PY_PORT="${PY_PARSER_PORT:-9091}"
GO_PORT="${GO_PARSER_PORT:-9092}"
TS_PORT="${TS_PARSER_PORT:-9093}"
PY_IMAGE="${PY_PARSER_IMAGE:-tester-python-parser:latest}"
GO_IMAGE="${GO_PARSER_IMAGE:-tester-go-parser:latest}"
TS_IMAGE="${TS_PARSER_IMAGE:-tester-ts-parser:latest}"

have_image() { docker image inspect "$1" >/dev/null 2>&1; }
running() { docker ps --format '{{.Names}}' | grep -qx "$1"; }
healthy() { [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://localhost:$1/health")" = "200" ]; }

build() {
  have_image "$PY_IMAGE" || docker build --build-arg REGISTRY="$MIRROR" -t "$PY_IMAGE" "$REPO/parsers/python" || return 1
  have_image "$GO_IMAGE" || docker build --build-arg REGISTRY="$MIRROR" -t "$GO_IMAGE" "$REPO/parsers/go" || return 1
  have_image "$TS_IMAGE" || docker build --build-arg REGISTRY="$MIRROR" -t "$TS_IMAGE" "$REPO/parsers/ts" || return 1
}

start_one() {
  local name="$1" port="$2" image="$3"
  running "$name" && { echo "UP $name :$port"; return 0; }
  docker rm -f "$name" >/dev/null 2>&1
  docker run -d --name "$name" -p "$port:$port" "$image" >/dev/null || return 1
  for _ in $(seq 1 30); do healthy "$port" && { echo "UP $name :$port"; return 0; }; sleep 1; done
  echo "$name did not become healthy; logs:"; docker logs --tail 20 "$name"; return 1
}

up() {
  build || { echo "parser image build failed"; exit 1; }
  start_one "$PY_NAME" "$PY_PORT" "$PY_IMAGE" || exit 1
  start_one "$GO_NAME" "$GO_PORT" "$GO_IMAGE" || exit 1
  start_one "$TS_NAME" "$TS_PORT" "$TS_IMAGE" || exit 1
}

down() {
  docker rm -f "$PY_NAME" "$GO_NAME" "$TS_NAME" >/dev/null 2>&1
  echo "down"
}

status() {
  for pair in "$PY_NAME:$PY_PORT" "$GO_NAME:$GO_PORT" "$TS_NAME:$TS_PORT"; do
    n="${pair%:*}"; p="${pair#*:}"
    running "$n" && healthy "$p" && echo "UP $n :$p" || echo "DOWN $n :$p"
  done
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  status) status ;;
  logs) docker logs --tail "${3:-40}" "tester-${2:-python}-parser" ;;
  *) echo "usage: $0 {up|down|status|logs [python|go] [n]}"; exit 2 ;;
esac
