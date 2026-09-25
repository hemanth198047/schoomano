# Shared helpers for the POSIX sh scripts (Linux, macOS, WSL, Git Bash). Sourced, not executed.
# shellcheck shell=sh

# Work from the repository root; every path below is relative to it.
cd -- "$(dirname -- "$0")/.." || exit 1
RUN_DIR=.run
COMPOSE_FILE=infra/docker-compose.yml
ENV_FILE=.env
WAIT_SECONDS="${SCHOOMANO_WAIT_SECONDS:-240}"

# Plain progress output: Docker Compose/BuildKit then need no interactive console
# (Git Bash/mintty, CI, redirected output).
COMPOSE_PROGRESS=plain
BUILDKIT_PROGRESS=plain
export COMPOSE_PROGRESS BUILDKIT_PROGRESS

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
    [ -f .env.example ] || die ".env.example is missing"
    owner=$(random_secret); app=$(random_secret)
    sed -e "s/^SCHOOMANO_DB_OWNER_PASSWORD=.*/SCHOOMANO_DB_OWNER_PASSWORD=$owner/" \
        -e "s/^SCHOOMANO_DB_APP_PASSWORD=.*/SCHOOMANO_DB_APP_PASSWORD=$app/" \
        .env.example >"$ENV_FILE"
    chmod 600 "$ENV_FILE" 2>/dev/null || true
    info "Created .env with random local database passwords"
  fi
}

# set_env_value KEY VALUE: updates (or appends) KEY in .env, portably (no sed -i).
set_env_value() {
  tmp="$ENV_FILE.tmp.$$"
  if grep -q "^$1=" "$ENV_FILE"; then
    sed "s|^$1=.*|$1=$2|" "$ENV_FILE" >"$tmp"
  else
    cp "$ENV_FILE" "$tmp"
    printf '%s=%s\n' "$1" "$2" >>"$tmp"
  fi
  mv "$tmp" "$ENV_FILE"
  chmod 600 "$ENV_FILE" 2>/dev/null || true
}

# shellcheck disable=SC2034  # DB_PORT etc. are used by the scripts that source this file
derive_ports() {
  : "${SCHOOMANO_DB_PORT:=5432}" "${SCHOOMANO_API_PORT:=8080}" "${SCHOOMANO_WEB_PORT:=8081}" "${SCHOOMANO_DEV_WEB_PORT:=5173}"
  for v in "$SCHOOMANO_DB_PORT" "$SCHOOMANO_API_PORT" "$SCHOOMANO_WEB_PORT" "$SCHOOMANO_DEV_WEB_PORT"; do
    case "$v" in ''|*[!0-9]*) die "Ports in .env must be numbers (found '$v')." ;; esac
  done
  DB_OWNER="${SCHOOMANO_DB_OWNER_USER:-schoomano_owner}"
  DB_PORT=$SCHOOMANO_DB_PORT
  API_PORT=$SCHOOMANO_API_PORT
  WEB_PORT=$SCHOOMANO_WEB_PORT
  DEV_WEB_PORT=$SCHOOMANO_DEV_WEB_PORT
}

load_env() {
  ensure_env
  set -a
  # shellcheck disable=SC1090
  . "./$ENV_FILE"
  set +a
  derive_ports
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

container_running() { [ -n "$(compose ps -q "$1" 2>/dev/null)" ]; }

http_ok() {
  if have curl; then curl -fsS -o /dev/null --max-time 3 "$1" 2>/dev/null
  elif have wget; then wget -q -O /dev/null -T 3 "$1" 2>/dev/null
  else die "Need curl or wget to check service health."
  fi
}

# ---- port availability ----

# True if something on this machine already accepts connections on 127.0.0.1:<port>.
port_in_use() {
  if have curl; then
    rc=0
    curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$1/" 2>/dev/null || rc=$?
    [ "$rc" -ne 7 ]            # 7 = connection refused, i.e. nothing is listening
  elif have nc; then
    nc -z 127.0.0.1 "$1" >/dev/null 2>&1
  else
    return 1
  fi
}

# choose_port VAR LABEL OWNED: keeps VAR's port if it is free or already used by Schoomano itself
# (OWNED is a command that succeeds in that case); otherwise picks the next free port and saves it to .env.
choose_port() {
  var=$1; label=$2; owned=$3; current=""
  eval "current=\${$var}"
  if $owned; then return 0; fi
  candidate=$current; tries=0
  while port_in_use "$candidate" || { [ "$candidate" != "$current" ] && in_list "$candidate" "$RESERVED"; }; do
    candidate=$((candidate + 1)); tries=$((tries + 1))
    [ "$tries" -le 100 ] || die "No free port found near $current for the $label."
  done
  if [ "$candidate" != "$current" ]; then
    warn "Port $current (for the $label) is in use by another program; using $candidate instead (saved in .env)."
    set_env_value "$var" "$candidate"
    eval "$var=\$candidate"
    RESERVED="$RESERVED $candidate"
  fi
}

in_list() { case " $2 " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

db_owned()      { container_running postgres; }
api_owned()     { container_running backend || running_pid backend >/dev/null; }
web_owned()     { container_running frontend; }
dev_web_owned() { running_pid frontend >/dev/null; }

# Makes sure every port Schoomano needs is available, moving any that another program holds.
resolve_ports() {
  RESERVED="$SCHOOMANO_DB_PORT $SCHOOMANO_API_PORT $SCHOOMANO_WEB_PORT $SCHOOMANO_DEV_WEB_PORT"
  choose_port SCHOOMANO_DB_PORT "database" db_owned
  choose_port SCHOOMANO_API_PORT "API" api_owned
  choose_port SCHOOMANO_WEB_PORT "web app" web_owned
  choose_port SCHOOMANO_DEV_WEB_PORT "dev web server" dev_web_owned
  export SCHOOMANO_DB_PORT SCHOOMANO_API_PORT SCHOOMANO_WEB_PORT SCHOOMANO_DEV_WEB_PORT
  derive_ports
}

# wait_http <name> <url> [pid]: polls until the URL answers 2xx; fails early if the pid dies.
wait_http() {
  name=$1; url=$2; pid=${3:-}
  info "Waiting for $name ($url), up to ${WAIT_SECONDS}s"
  elapsed=0
  while [ "$elapsed" -lt "$WAIT_SECONDS" ]; do
    if http_ok "$url"; then info "$name is up"; return 0; fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      die "$name exited during startup. See $RUN_DIR/$name.log"
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

# psql inside the database container, as the schema owner (bypasses RLS: seeding and tests only).
psql_in_db() { compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$DB_OWNER" -d schoomano "$@"; }

# ---- local (dev-mode) processes, tracked with pid files in .run/ ----

pid_file() { printf '%s/%s.pid' "$RUN_DIR" "$1"; }

# Prints the pid if the tracked process is alive and still looks like ours (guards against pid reuse).
running_pid() {
  f=$(pid_file "$1"); [ -f "$f" ] || return 1
  pid=$(cat "$f"); [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null || { rm -f "$f"; return 1; }
  case "$1" in
    backend)  marker=schoomano.jar ;;
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
