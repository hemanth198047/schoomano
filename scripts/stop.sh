#!/bin/sh
# Stop Schoomano.
#
#   scripts/stop.sh                 stop everything (local --dev processes and containers); data is kept
#   scripts/stop.sh backend         stop one component; also: db, frontend
#   scripts/stop.sh --purge         also DELETE the local database volume (asks first; --yes skips the question)
set -eu
. "$(dirname -- "$0")/lib/common.sh"

usage() { sed -n '2,7p' scripts/stop.sh | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

TARGET=all; PURGE=0; YES=0
for arg in "$@"; do
  case "$arg" in
    --purge|-Purge|-purge) PURGE=1 ;;
    --yes|-y|-Yes) YES=1 ;;
    -h|--help) usage 0 ;;
    -*) warn "unknown option $arg"; usage 2 ;;
    *) TARGET=$(component "$arg") ;;
  esac
done
[ "$PURGE" = 1 ] && [ "$TARGET" != all ] && die "--purge applies to the whole stack only"

load_env

case "$TARGET" in
  all) stop_process frontend; stop_process backend ;;
  backend|frontend) stop_process "$TARGET" ;;
esac

if ! have docker || ! docker info >/dev/null 2>&1; then
  warn "Docker is not running; skipped containers."
  exit 0
fi

case "$TARGET" in
  all)
    if [ "$PURGE" = 1 ]; then
      if [ "$YES" != 1 ]; then
        printf 'This deletes the LOCAL Schoomano database volume. Type "delete" to confirm: '
        read -r answer
        [ "$answer" = delete ] || die "Cancelled; nothing deleted."
      fi
      info "Removing containers and the local database volume"
      compose down -v --remove-orphans
    else
      info "Stopping containers (database volume kept)"
      compose down --remove-orphans
    fi ;;
  db) info "Stopping database container"; compose stop postgres ;;
  backend) info "Stopping backend container"; compose stop backend ;;
  frontend) info "Stopping frontend container"; compose stop frontend ;;
esac
info "Stopped"
