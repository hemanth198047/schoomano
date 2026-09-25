#!/bin/sh
# Start Schoomano locally.
#
#   scripts/start.sh                    full stack in containers (db + backend + frontend)
#   scripts/start.sh --dev              db in a container; backend and frontend as local processes (hot reload for web)
#   scripts/start.sh [--dev] backend    one component (and what it needs); also: db, frontend
#   options: --seed      load synthetic demo data once the backend has migrated the database
#            --no-build  reuse existing images / backend jar
set -eu
. "$(dirname -- "$0")/lib/common.sh"

usage() { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

MODE=containers; SEED=0; BUILD=1; TARGET=all
for arg in "$@"; do
  case "$arg" in
    --dev) MODE=dev ;;
    --seed) SEED=1 ;;
    --no-build) BUILD=0 ;;
    -h|--help) usage 0 ;;
    -*) warn "unknown option $arg"; usage 2 ;;
    *) TARGET=$(component "$arg") ;;
  esac
done

load_env
require_docker
mkdir -p "$RUN_DIR"
[ "$SEED" = 1 ] && [ "$TARGET" = db ] && die "--seed needs the backend (it creates the schema). Use: scripts/start.sh --seed"

BUILD_FLAG=""; [ "$BUILD" = 1 ] && BUILD_FLAG="--build"
API_HEALTH="http://127.0.0.1:$API_PORT/actuator/health/readiness"

start_containers() {
  for p in backend frontend; do
    running_pid "$p" >/dev/null && die "A local $p (--dev) is running on the same port. Run scripts/stop.sh first."
  done
  case "$TARGET" in
    db)
      info "Starting database container"
      compose up -d postgres; wait_db ;;
    backend)
      info "Starting database and backend containers"
      # shellcheck disable=SC2086
      compose up -d $BUILD_FLAG postgres backend
      wait_http backend "$API_HEALTH" ;;
    frontend|all)
      info "Starting database, backend and frontend containers (first build downloads dependencies and can take several minutes)"
      # shellcheck disable=SC2086
      compose up -d $BUILD_FLAG
      wait_http backend "$API_HEALTH"
      wait_http frontend "http://127.0.0.1:$WEB_PORT/healthz" ;;
  esac
}

start_dev_backend() {
  if pid=$(running_pid backend); then info "Local backend already running (pid $pid)"; return 0; fi
  if [ -n "$(compose ps -q backend 2>/dev/null)" ]; then
    die "The backend container is running on port $API_PORT. Run scripts/stop.sh backend first."
  fi
  have java || die "Java 21 (JDK) is required for --dev backend."
  jar="$ROOT/backend/schoomano-app/target/schoomano-app.jar"
  if [ "$BUILD" = 1 ] || [ ! -f "$jar" ]; then
    have mvn || die "Maven 3.9+ is required for --dev backend (or use container mode: scripts/start.sh)."
    info "Building backend (mvn package, tests skipped; run 'cd backend && mvn verify' for tests)"
    mvn -B -q -f "$ROOT/backend/pom.xml" -DskipTests package
  fi
  info "Starting local backend on port $API_PORT"
  SCHOOMANO_PORT="$API_PORT" nohup java -jar "$jar" >"$RUN_DIR/backend.log" 2>&1 &
  echo $! >"$(pid_file backend)"
  wait_http backend "$API_HEALTH" "$(cat "$(pid_file backend)")"
}

start_dev_frontend() {
  if pid=$(running_pid frontend); then info "Local frontend already running (pid $pid)"; return 0; fi
  have node || die "Node.js 22+ is required for --dev frontend."
  have npm || die "npm is required for --dev frontend."
  cd "$ROOT/frontend"
  if [ ! -d node_modules ]; then info "Installing frontend dependencies"; npm ci --no-audit --no-fund; fi
  npm run -s tokens >/dev/null
  info "Starting local frontend (Vite) on port $DEV_WEB_PORT"
  SCHOOMANO_BACKEND_URL="http://127.0.0.1:$API_PORT" VITE_DEV_AUTH="${SCHOOMANO_AUTH_DEV_MODE:-false}" \
    nohup node node_modules/vite/bin/vite.js --port "$DEV_WEB_PORT" --strictPort >"$RUN_DIR/frontend.log" 2>&1 &
  echo $! >"$(pid_file frontend)"
  cd "$ROOT"
  wait_http frontend "http://127.0.0.1:$DEV_WEB_PORT/" "$(cat "$(pid_file frontend)")"
}

if [ "$MODE" = containers ]; then
  start_containers
else
  info "Starting database container"
  compose up -d postgres
  wait_db
  case "$TARGET" in
    backend) start_dev_backend ;;
    frontend) start_dev_frontend ;;
    all) start_dev_backend; start_dev_frontend ;;
  esac
fi

[ "$SEED" = 1 ] && "$ROOT/scripts/seed.sh"

say ""
info "Schoomano is running ($MODE mode)"
case "$MODE:$TARGET" in
  containers:all|containers:frontend) say "  Web app:  http://localhost:$WEB_PORT" ;;
  dev:all|dev:frontend)               say "  Web app:  http://localhost:$DEV_WEB_PORT  (hot reload)" ;;
esac
case "$TARGET" in all|backend) say "  API:      http://localhost:$API_PORT/actuator/health" ;; esac
say "  Database: localhost:${SCHOOMANO_DB_PORT:-5432} (db schoomano)"
say "  Stop with: scripts/stop.sh    Status: scripts/status.sh"
