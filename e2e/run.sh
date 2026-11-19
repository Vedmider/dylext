#!/bin/bash
# Runs the developer-activity gap finder against a throwaway pgvector, then tears it down.
#   bash e2e/run.sh                     all cases
#   bash e2e/run.sh --only O,B05        a subset (group letters or case ids)
#   bash e2e/run.sh --report docs/DEV-ACTIVITY-GAP-REPORT.md
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Optional LOCAL, uncommitted overrides (e.g. an internal registry mirror). See .local.env.example.
[ -f "$HERE/../.local.env" ] && . "$HERE/../.local.env"
MIRROR="${MIRROR:-docker.io}"
PGVECTOR_IMAGE="${PGVECTOR_IMAGE:-$MIRROR/pgvector/pgvector:pg16}"
PG=dylext-devact-pg
PORT="${PG_PORT:-55433}"

# the jar needs JDK 21; use the repo's env.sh (same as the build) when present
[ -f "$HERE/../env.sh" ] && source "$HERE/../env.sh"

docker rm -f "$PG" >/dev/null 2>&1 || true
docker run -d --name "$PG" -p "$PORT:5432" -e POSTGRES_DB=dylext -e POSTGRES_USER=dylext \
  -e POSTGRES_PASSWORD=dylext "$PGVECTOR_IMAGE" >/dev/null || { echo "could not start postgres"; exit 1; }
trap 'docker rm -f "$PG" >/dev/null 2>&1' EXIT

for _ in $(seq 1 40); do
  docker exec "$PG" pg_isready -U dylext >/dev/null 2>&1 && break
  sleep 1
done

DB_URL="jdbc:postgresql://localhost:$PORT/dylext" python3 "$HERE/dev_activity.py" "$@"
