#!/bin/sh
# Show what is running: local --dev processes, containers, and health endpoints.
set -eu
. "$(dirname -- "$0")/lib/common.sh"
load_env

info "Local processes (--dev)"
for p in backend frontend; do
  if pid=$(running_pid "$p"); then say "  $p: running (pid $pid, log $RUN_DIR/$p.log)"; else say "  $p: not running"; fi
done

info "Containers"
if have docker && docker info >/dev/null 2>&1; then compose ps; else say "  Docker is not running"; fi

info "Health"
check() { if http_ok "$2"; then say "  $1: OK   $2"; else say "  $1: --   $2"; fi; }
check "API" "http://127.0.0.1:$API_PORT/actuator/health/readiness"
check "Web (containers)" "http://127.0.0.1:$WEB_PORT/healthz"
check "Web (--dev)" "http://127.0.0.1:$DEV_WEB_PORT/"
