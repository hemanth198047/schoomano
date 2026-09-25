# Shared helpers for the POSIX sh scripts (Linux, macOS, WSL, Git Bash). Sourced, not executed.
# shellcheck shell=sh

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
RUN_DIR="$ROOT/.run"
COMPOSE_FILE="$ROOT/infra/docker-compose.yml"
ENV_FILE="$ROOT/.env"
WAIT_SECONDS="${SCHOOMANO_WAIT_SECONDS:-240}"

say()  { printf '%s\n' "$*"; }
info() { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

random_secret() {
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom 2>/dev/null | head -c 24
}

# Creates .env from .env.example on first run, replacing placeholder passwords with random ones.
ensure_env() {
  if [ ! -f "$ENV_FILE" ]; then
    [ -f "$ROOT/.env.example" ] || die ".env.example is missing"
    owner=$(random_secret); app=$(random_secret)
    sed -e "s/^SCHOOMANO_DB_OWNER_PASSWORD=.*/SCHOOMANO_DB_OWNER_PASSWORD=$owner/" \
        -e "s/^SCHOOMANO_DB_APP_PASSWORD=.*/SCHOOMANO_DB_APP_PASSWORD=$app/" \
        "$ROOT/.env.example" >"$ENV_FILE"
    chmod 600 "$ENV_FILE" 2>/dev/null || true
    info "Created .env with random local database passwords"
  fi
}

load_env() {
  ensure_env
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  DB_OWNER="${SCHOOMANO_DB_OWNER_USER:-schoomano_owner}"
  # shellcheck disable=SC2034  # used by the scripts that source this file
  API_PORT="${SCHOOMANO_API_PORT:-8080}"
  # shellcheck disable=SC2034
  WEB_PORT="${SCHOOMANO_WEB_PORT:-8081}"
  # shellcheck disable=SC2034
  DEV_WEB_PORT=5173
}

# docker compose v2 plugin, falling back to legacy docker-compose.
compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -p schoomano "$@"
  elif have docker-compose; then
    docker-compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -p schoomano "$@"
  else
    die "Docker Compose not found. Install Docker Desktop (Windows/macOS) or Docker Engine with the compose plugin (Linux)."
  fi
}

require_docker() {
  have docker || die "Docker is not installed or not on PATH."
  docker info >/dev/null 2>&1 || die "Docker is installed but not running. Start Docker Desktop / the docker service and retry."
}

http_ok() {
  if have curl; then curl -fsS -o /dev/null --max-time 3 "$1" 2>/dev/null
  elif have wget; then wget -q -O /dev/null -T 3 "$1" 2>/dev/null
  else die "Need curl or wget to check service health."
  fi
}

# wait_http <name> <url> [pid]: polls until the URL answers 2xx; fails early if the pid dies.
wait_http() {
  name=$1; url=$2; pid=${3:-}
  info "Waiting for $name ($url), up to ${WAIT_SECONDS}s"
  elapsed=0
  while [ "$elapsed" -lt "$WAIT_SECONDS" ]; do
    if http_ok "$url"; then info "$name is up"; return 0; fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      die "$name exited during startup. See .run/$name.log"
    fi
    sleep 2; elapsed=$((elapsed + 2))
  done
  die "$name did not become ready in ${WAIT_SECONDS}s. Check: scripts/logs.sh $name"
}

wait_db() {
  info "Waiting for PostgreSQL"
  elapsed=0
  while [ "$elapsed" -lt "$WAIT_SECONDS" ]; do
    if compose exec -T postgres pg_isready -q -U "$DB_OWNER" -d schoomano >/dev/null 2>&1; then
      info "PostgreSQL is up"; return 0
    fi
    sleep 2; elapsed=$((elapsed + 2))
  done
  die "PostgreSQL did not become ready. Check: scripts/logs.sh db"
}

# ---- local (dev-mode) processes, tracked with pid files in .run/ ----

# psql inside the database container, as the schema owner (bypasses RLS: seeding and tests only).
psql_in_db() { compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$DB_OWNER" -d schoomano "$@"; }

pid_file() { printf '%s/%s.pid' "$RUN_DIR" "$1"; }

# Prints the pid if the tracked process is alive and still looks like ours (guards against pid reuse).
running_pid() {
  f=$(pid_file "$1"); [ -f "$f" ] || return 1
  pid=$(cat "$f"); [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || { rm -f "$f"; return 1; }
  case "$1" in
    backend)  marker=schoomano-app.jar ;;
    frontend) marker=vite ;;
    *)        marker= ;;
  esac
  # Where `ps -o` is unsupported (e.g. Git Bash) the command line cannot be checked; trust the pid.
  if [ -n "$marker" ] && have ps; then
    args=$(ps -p "$pid" -o args= 2>/dev/null) || args=""
    if [ -n "$args" ] && ! printf '%s' "$args" | grep -q "$marker"; then rm -f "$f"; return 1; fi
  fi
  printf '%s' "$pid"
}

stop_process() {
  name=$1
  if pid=$(running_pid "$name"); then
    info "Stopping local $name (pid $pid)"
    kill "$pid" 2>/dev/null || true
    i=0
    while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 30 ]; do sleep 1; i=$((i + 1)); done
    if kill -0 "$pid" 2>/dev/null; then warn "$name did not stop gracefully; forcing"; kill -9 "$pid" 2>/dev/null || true; fi
    rm -f "$(pid_file "$name")"
  fi
}

# Normalises component names; "db" and "postgres" are the same thing.
component() {
  case "${1:-all}" in
    all|"") echo all ;;
    db|database|postgres) echo db ;;
    backend|api) echo backend ;;
    frontend|web) echo frontend ;;
    *) die "Unknown component '$1'. Use: all, db, backend, frontend" ;;
  esac
}
