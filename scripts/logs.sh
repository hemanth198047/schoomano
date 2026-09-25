#!/bin/sh
# Show logs:  scripts/logs.sh [backend|frontend|db] [-f]
# Uses the local --dev log if that component runs locally, otherwise the container log.
set -eu
. "$(dirname -- "$0")/lib/common.sh"
load_env
TARGET=all; FOLLOW=""
for arg in "$@"; do
  case "$arg" in -f|--follow) FOLLOW=1 ;; *) TARGET=$(component "$arg") ;; esac
done
if { [ "$TARGET" = backend ] || [ "$TARGET" = frontend ]; } && running_pid "$TARGET" >/dev/null; then
  if [ -n "$FOLLOW" ]; then exec tail -f "$RUN_DIR/$TARGET.log"; else exec tail -n 200 "$RUN_DIR/$TARGET.log"; fi
fi
svc=""
case "$TARGET" in db) svc=postgres ;; backend|frontend) svc=$TARGET ;; esac
if [ -n "$FOLLOW" ]; then
  # shellcheck disable=SC2086
  compose logs -f --tail=200 $svc
else
  # shellcheck disable=SC2086
  compose logs --tail=200 $svc
fi
