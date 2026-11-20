#!/bin/bash
# dylext-gateway lifecycle for the agents: up | down | status | logs.
# The gateway is the SINGLE INGRESS (:18070) in front of Dylext (/mcp) and Sestacon (/api).
GW=/local/IdeaProjects/dylext-gateway
JAR="$GW/target/dylext-gateway-0.2.0.jar"
LOG=/tmp/dylext-gateway.log
PORT=18070
ENV=/local/IdeaProjects/dylext/env.sh
MATCH='[d]ylext-gateway-.*\.jar'   # any version; bracket trick never matches this script's own cmdline

case "${1:-}" in
  up)
    if pgrep -f "$MATCH" >/dev/null 2>&1; then echo "already up http://localhost:$PORT"; exit 0; fi
    [ -f "$JAR" ] || ( cd "$GW" && { [ -f "$ENV" ] && source "$ENV"; } ; mvn -B -q -DskipTests package )
    [ -f "$ENV" ] && source "$ENV"
    nohup java -jar "$JAR" >"$LOG" 2>&1 </dev/null & disown
    for _ in $(seq 1 30); do
      curl -s -m3 http://localhost:$PORT/actuator/health 2>/dev/null | grep -q UP && {
        echo "UP http://localhost:$PORT (pid $(pgrep -f "$MATCH" | head -1))"; exit 0; }
      sleep 1
    done
    echo "gateway failed to come up; see $LOG"; exit 1 ;;
  down)    pkill -9 -f "$MATCH" 2>/dev/null; echo down ;;
  status)  curl -s -m3 http://localhost:$PORT/actuator/health 2>/dev/null || echo "down" ;;
  logs)    tail -n 40 "$LOG" 2>/dev/null ;;
  *)       echo "usage: $0 {up|down|status|logs}"; exit 2 ;;
esac
