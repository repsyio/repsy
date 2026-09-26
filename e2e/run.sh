#!/usr/bin/env bash
# Copyright 2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Single entry point for the e2e harness: local | remote | (later) ci. The host needs Docker only;
# tests only ever run inside a runner container (docker-compose.runners.yml), never on the host.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

STACK_FILE="docker-compose.stack.yml"
STACK_FILE_H2="docker-compose.stack-h2.yml"
RUNNERS_FILE="docker-compose.runners.yml"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

# The stack's compose project and its host-port offset (README.md "Parallel stacks"). The defaults are
# what the compose files always used: project repsy-e2e on 8080/9090. Several worktrees on one machine
# each pick their own pair, or a second "local up" replaces the first one's containers (RPS-1422).
# Both are also settable per call: --project NAME, --port-offset N (see extract_global_options).
DEFAULT_PROJECT="repsy-e2e"
PROJECT="${REPSY_E2E_PROJECT:-$DEFAULT_PROJECT}"
PORT_OFFSET="${REPSY_E2E_PORT_OFFSET:-0}"
# The compose project of the runner containers (docker-compose.runners.yml); see derive_stack_env.
RUNNERS_PROJECT="repsy-e2e-runners"

# Read by docker-compose.runners.yml's "stack" runner: this directory, mounted read-only at the same path,
# so that runner can run `docker compose` on the stack's own files (recreateRepsy, RPS-1476).
REPSY_E2E_HOST_DIR="$SCRIPT_DIR"
export REPSY_E2E_HOST_DIR

# Read by docker-compose.runners.yml's "user:", so a runner container writes the regenerated API
# client and test reports as this user, not as the image's default root (see that file's comment).
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"
export HOST_UID HOST_GID

# Read by docker-compose.runners.yml's "stack" runner (group_add), so its non-root uid can use the
# host's Docker socket. Only that runner mounts the socket. A daemon reached some other way
# (DOCKER_HOST, rootless Docker) is not supported by that runner.
if [ -z "${DOCKER_GID:-}" ] && [ -S /var/run/docker.sock ]; then
  DOCKER_GID="$(stat -c '%g' /var/run/docker.sock 2>/dev/null || stat -f '%g' /var/run/docker.sock)"
  export DOCKER_GID
fi

usage() {
  cat <<'EOF'
Usage:
  run.sh local up|down [--h2] [--scanner] [--throttle] [--tls] [--limits] [--upgrade] [--trivy] [--force]
  run.sh local logs|ps [--h2] [--scanner] [--throttle] [--tls] [--limits] [--upgrade] [--trivy]
  run.sh test [--target local|remote|ci] [--protocol a,b] [--grep PATTERN] [-b]
  run.sh sweep [--hours N] [--all] [--dry-run]

Every subcommand also takes --project NAME and --port-offset N (or REPSY_E2E_PROJECT and
REPSY_E2E_PORT_OFFSET), anywhere on the line.

--protocol takes runner service names: skeleton, maven, npm, npm-clients (the npm registry under pnpm,
yarn classic, yarn berry and bun as well as npm; see README.md "npm-family clients"), cargo, nuget,
docker, helm, pypi, golang, ruby, stack (cases that docker-exec into the Repsy container, tests/stack;
local stack only, see README.md "Stack runner") ui (the panel UI suite in headless Chromium,
tests/ui; see README.md "UI suite") and api (raw HTTP at Repsy's edge, no package client: port separation,
X-Forwarded-* public URLs, CORS/CSP; tests/api, see README.md "API suite").

REPSY_ADMIN_PASSWORD must be set (copy .env.example to .env and fill it in) for every subcommand
except "local down".

"local up|down" starts/stops the postgres profile by default (docker-compose.stack.yml). Pass
--h2, or set REPSY_E2E_STACK=h2, to use the embedded-H2 profile (docker-compose.stack-h2.yml)
instead -- same ports/image, no postgres service, a fresh H2 database on every "up". "run.sh test"
needs no flag either way: both profiles serve the same REPSY_API_BASE_URL/REPSY_REPO_BASE_URL, so
every runner is unchanged.

Pass --scanner, or set REPSY_E2E_SCANNER=1, to add the opt-in stub scanner (docker-compose.stack-scanner.yml,
combinable with --h2): Repsy starts with SECURITY_SCANNER=enabled pointed at a deterministic stand-in for
repsy-scanner-trivy, which the @scanner UI specs need (REPSY_E2E_OPT_IN=scanner; with REPSY_E2E_SCANNER=1
"run.sh test" adds that opt-in itself). The default stack never starts a scanner. Give "down" the same
flags as "up". See README.md "Scanner stack".

--trivy (or REPSY_E2E_TRIVY=1) is the alternative to --scanner: the REAL repsy-scanner-trivy service, built from
../repsy-scanner-trivy, with Repsy pointed at it (docker-compose.stack-trivy.yml, RPS-1484), for the @trivy
contract spec of the api runner. It needs the network (Trivy downloads its vulnerability database) and cannot
be combined with --scanner. See README.md "Real scanner stack".

"local logs" prints the container logs (with timestamps) and "local ps" lists the containers (stopped ones
too) of the stack that "local up" started with the same flags. The nightly workflow collects its stack
logs with them, so a stack file added here needs no change there.

--throttle (or REPSY_E2E_THROTTLE=1) is the second overlay: Repsy starts with the auth throttle at 3 failed
password checks per 10 s per client (docker-compose.stack-throttle.yml), for the @throttle specs of the
stack and ui runners. Overlays are rows of the OVERLAYS table at the top of this script; "run.sh test" turns
every overlay whose switch is set into an entry of REPSY_E2E_OPT_IN (a comma list the runners read, which
also takes a name directly, e.g. REPSY_E2E_OPT_IN=a11y-report). See README.md "Stack overlays".

--tls (or REPSY_E2E_TLS=1) is the third overlay: Repsy's own HTTPS listeners (8443 next to the panel API, 9443
next to the repo protocols, moved by the port offset; HTTP stays open) with a throwaway CA and certificate
generated into e2e/.tls/<project> (docker-compose.stack-tls.yml). With the switch set, "run.sh test" and
"run.sh sweep" point the runners at the https URLs (the http ones stay in REPSY_E2E_PLAIN_*) and make every
client trust that CA in its own way. Give "test" the same switch as "up" (REPSY_E2E_TLS=1). The ui runner
is left out. See README.md "TLS stack".

--limits (or REPSY_E2E_LIMITS=1) is the fourth overlay: Repsy starts with tiny upload size limits, 64 KiB for a
PyPI/Helm/NuGet upload, a gem, a crate and a Go module zip (docker-compose.stack-limits.yml), for the @limits
specs of the pypi, helm, nuget, ruby, cargo, golang and api runners: an over-limit push gets a 413. No other
suite may run there. See README.md "Size-limit leg".

--upgrade (or REPSY_E2E_UPGRADE=1) is the upgrade-path overlay (docker-compose.stack-upgrade.yml, RPS-1487): "local up"
starts the PREVIOUS release on a fresh volume (the tag in src/upgrade/previous-release.ts, or
REPSY_E2E_UPGRADE_FROM=<tag>) and builds the image under test
(REPSY_IMAGE when set, else repsy-os-e2e:<project tag>) without starting it; tests/stack/upgrade.spec.ts
then recreates the container on that image. See README.md "Upgrade path".

Parallel stacks (README.md "Parallel stacks"): the stack is the compose project --project NAME
(default repsy-e2e) with its host ports moved up by --port-offset N (default 0: panel API 8080, repo
protocols 9090, stub scanner 8090). Two checkouts that run stacks at the same time each need their
own pair, e.g. REPSY_E2E_PROJECT=rps-1 REPSY_E2E_PORT_OFFSET=100, given to "local up|down", "test"
and "sweep" alike so all of them reach that stack. "local up|down" refuse to touch a project (or a
host port) that a stack started from another checkout holds; --force (or REPSY_E2E_FORCE=1) overrides.
EOF
}

# The opt-in overlays of the stack (README.md "Stack overlays"): compose files layered on either stack file
# (postgres or H2) that change what Repsy runs with for one leg. One row per overlay, "name|flag|env
# switch|compose file". "local up|down" take the flag (or the switch set in the environment); "test" reads
# the switches only, and turns each active overlay's name into an entry of REPSY_E2E_OPT_IN, the comma
# list of opt-in suites every runner reads (src/stack-overlays.ts optedIn()), so a spec that needs an
# overlay skips itself without it. A new overlay is one row and one compose file.
OVERLAYS=(
  "scanner|--scanner|REPSY_E2E_SCANNER|docker-compose.stack-scanner.yml"
  "throttle|--throttle|REPSY_E2E_THROTTLE|docker-compose.stack-throttle.yml"
  "tls|--tls|REPSY_E2E_TLS|docker-compose.stack-tls.yml"
  "limits|--limits|REPSY_E2E_LIMITS|docker-compose.stack-limits.yml"
  "upgrade|--upgrade|REPSY_E2E_UPGRADE|docker-compose.stack-upgrade.yml"
  "trivy|--trivy|REPSY_E2E_TRIVY|docker-compose.stack-trivy.yml"
)

# Field $2 (1 name, 2 flag, 3 env switch, 4 file) of the overlay row $1.
overlay_field() {
  local IFS='|'
  local -a fields
  read -ra fields <<< "$1"
  printf '%s' "${fields[$(($2 - 1))]}"
}

# True when the overlay called $1 is switched on for this call (see parse_stack_flags).
overlay_active() {
  local name
  for name in ${ACTIVE_OVERLAYS[@]+"${ACTIVE_OVERLAYS[@]}"}; do
    [ "$name" = "$1" ] && return 0
  done
  return 1
}

# True when an env switch such as REPSY_E2E_SCANNER is set to something other than off.
env_switch_on() {
  case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
    '' | 0 | false | no | off) return 1 ;;
    *) return 0 ;;
  esac
}

# Parses the flags of "local up|down" into USE_H2 ("true"/"false"), FORCE and ACTIVE_OVERLAYS (the names
# of the OVERLAYS rows that are on). All of them can also be switched on from the environment:
# REPSY_E2E_STACK=h2 and each overlay's switch (REPSY_E2E_SCANNER=1, REPSY_E2E_THROTTLE=1, ...).
USE_H2="false"
FORCE="false"
ACTIVE_OVERLAYS=()
parse_stack_flags() {
  local sub="$1"
  shift
  USE_H2="false"
  FORCE="false"
  ACTIVE_OVERLAYS=()
  local arg row name known
  for arg in "$@"; do
    known="false"
    for row in "${OVERLAYS[@]}"; do
      if [ "$arg" = "$(overlay_field "$row" 2)" ]; then
        name="$(overlay_field "$row" 1)"
        overlay_active "$name" || ACTIVE_OVERLAYS+=("$name")
        known="true"
      fi
    done
    [ "$known" = "true" ] && continue
    case "$arg" in
      --h2) USE_H2="true" ;;
      --force) FORCE="true" ;;
      *)
        echo "Unknown option for 'local $sub': $arg" >&2
        usage
        exit 1
        ;;
    esac
  done
  [ "${REPSY_E2E_STACK:-}" = "h2" ] && USE_H2="true"
  local switch
  for row in "${OVERLAYS[@]}"; do
    switch="$(overlay_field "$row" 3)"
    name="$(overlay_field "$row" 1)"
    if env_switch_on "${!switch:-}"; then
      overlay_active "$name" || ACTIVE_OVERLAYS+=("$name")
    fi
  done
  env_switch_on "${REPSY_E2E_FORCE:-}" && FORCE="true"
  return 0
}

# Takes --project NAME and --port-offset N (also as --project=NAME) out of the command line, wherever
# they stand, into PROJECT and PORT_OFFSET. Fills REMAINING_ARGS with the rest.
REMAINING_ARGS=()
extract_global_options() {
  REMAINING_ARGS=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --project | --port-offset)
        if [ $# -lt 2 ]; then
          echo "$1 needs a value" >&2
          exit 1
        fi
        if [ "$1" = "--project" ]; then PROJECT="$2"; else PORT_OFFSET="$2"; fi
        shift 2
        ;;
      --project=*)
        PROJECT="${1#--project=}"
        shift
        ;;
      --port-offset=*)
        PORT_OFFSET="${1#--port-offset=}"
        shift
        ;;
      *)
        REMAINING_ARGS+=("$1")
        shift
        ;;
    esac
  done
}

# Validates PROJECT and PORT_OFFSET and exports everything that follows from them, so "local up|down",
# "test" and "sweep" (and the runner containers behind them) all reach the same stack with no other
# change. A variable the user already set (typically REPSY_API_BASE_URL for a remote target) wins.
#   REPSY_E2E_API_PORT / REPSY_E2E_REPO_PORT   host ports of the stack (8080/9090 + offset), read by
#                                              the stack compose files
#   REPSY_E2E_SCANNER_PORT                     host port of the stub scanner (8090 + offset)
#   REPSY_E2E_API_TLS_PORT / _REPO_TLS_PORT    host ports of the TLS overlay's https listeners (8443/9443 + offset)
#   REPSY_API_BASE_URL / REPSY_REPO_BASE_URL   what the runners call; the stack also prints the latter
#                                              in the panel's client snippets (REPO_BASE_URL)
#   REPSY_E2E_STACK_PROJECT                    the project the stack runner docker-execs into
#   REPSY_E2E_IMAGE_TAG                        tag of the locally built images: "local" for the default
#                                              project, else the project, so two checkouts building at
#                                              once cannot retag each other's image
# The runner containers get their own compose project per stack project ("<project>-runners"), so the
# shared Maven/Gradle caches and the runner images are private to it too, not swapped by a branch that
# changes a runner Dockerfile. The default project keeps repsy-e2e-runners.
derive_stack_env() {
  case "$PROJECT" in
    '' | *[!a-z0-9_-]* | [-_]*)
      echo "Invalid project name \"$PROJECT\": lower-case letters, digits, '-' and '_', starting with a letter or digit (compose's rule)." >&2
      exit 1
      ;;
  esac
  case "$PORT_OFFSET" in
    '' | *[!0-9]*)
      echo "Invalid port offset \"$PORT_OFFSET\": a non-negative integer." >&2
      exit 1
      ;;
  esac
  # 10# so that 0100 is a hundred, not an invalid octal number. Five digits cannot overflow. 9443 is the
  # highest port of the stack (the TLS overlay's repo port), so the cap is computed on it.
  if [ "${#PORT_OFFSET}" -gt 5 ] || [ $((10#$PORT_OFFSET)) -gt $((65535 - 9443)) ]; then
    echo "Invalid port offset \"$PORT_OFFSET\": 9443 + offset must stay at or below 65535." >&2
    exit 1
  fi
  PORT_OFFSET=$((10#$PORT_OFFSET))

  local api_port=$((8080 + PORT_OFFSET)) repo_port=$((9090 + PORT_OFFSET))
  REPSY_E2E_API_PORT="$api_port"
  REPSY_E2E_REPO_PORT="$repo_port"
  REPSY_E2E_SCANNER_PORT="${REPSY_E2E_SCANNER_PORT:-$((8090 + PORT_OFFSET))}"
  REPSY_E2E_API_TLS_PORT=$((8443 + PORT_OFFSET))
  REPSY_E2E_REPO_TLS_PORT=$((9443 + PORT_OFFSET))
  # Whether the user chose the base URLs, which apply_tls_env then leaves alone.
  API_URL_EXPLICIT="${REPSY_API_BASE_URL:+true}"
  REPO_URL_EXPLICIT="${REPSY_REPO_BASE_URL:+true}"
  # A URL that is already set wins, but with an offset it usually is a leftover from a copied
  # .env.example and points at some other stack: say so instead of testing the wrong one silently.
  REPSY_API_BASE_URL="${REPSY_API_BASE_URL:-http://localhost:$api_port}"
  REPSY_REPO_BASE_URL="${REPSY_REPO_BASE_URL:-http://localhost:$repo_port}"
  if [ "$PORT_OFFSET" -ne 0 ]; then
    if [ "$REPSY_API_BASE_URL" != "http://localhost:$api_port" ]; then
      echo "Warning: REPSY_API_BASE_URL=$REPSY_API_BASE_URL is set, so it is used instead of http://localhost:$api_port (port offset $PORT_OFFSET). Unset it (e2e/.env?) to follow the offset." >&2
    fi
    if [ "$REPSY_REPO_BASE_URL" != "http://localhost:$repo_port" ]; then
      echo "Warning: REPSY_REPO_BASE_URL=$REPSY_REPO_BASE_URL is set, so it is used instead of http://localhost:$repo_port (port offset $PORT_OFFSET). Unset it (e2e/.env?) to follow the offset." >&2
    fi
  fi
  REPSY_E2E_STACK_PROJECT="${REPSY_E2E_STACK_PROJECT:-$PROJECT}"
  if [ "$PROJECT" = "$DEFAULT_PROJECT" ]; then
    REPSY_E2E_IMAGE_TAG="${REPSY_E2E_IMAGE_TAG:-local}"
  else
    RUNNERS_PROJECT="$PROJECT-runners"
    REPSY_E2E_IMAGE_TAG="${REPSY_E2E_IMAGE_TAG:-$PROJECT}"
  fi
  # REPSY_E2E_PROJECT is deliberately not COMPOSE_PROJECT_NAME: .env is sourced into this environment,
  # and COMPOSE_PROJECT_NAME would also rename the runners project. Every compose call below passes
  # -p instead.
  export REPSY_E2E_PROJECT="$PROJECT" REPSY_E2E_PORT_OFFSET="$PORT_OFFSET"
  export REPSY_E2E_API_PORT REPSY_E2E_REPO_PORT REPSY_E2E_SCANNER_PORT
  export REPSY_E2E_API_TLS_PORT REPSY_E2E_REPO_TLS_PORT
  export REPSY_API_BASE_URL REPSY_REPO_BASE_URL REPSY_E2E_STACK_PROJECT REPSY_E2E_IMAGE_TAG
}

# The TLS overlay (docker-compose.stack-tls.yml, README.md "TLS stack"): where its certificates live and
# what the runners are pointed at. Called once the flags (up|down|logs|ps) or the switch (test|sweep) say
# the overlay is on. The certificates are per stack project, in e2e/.tls/<project> (git-ignored), which
# has to exist before compose starts, or Docker would create it as root and the one-shot generator, which
# runs as the invoking user, could not write into it.
TLS_DIR=""
apply_tls_env() {
  TLS_DIR="$SCRIPT_DIR/.tls/$PROJECT"
  REPSY_E2E_TLS_DIR="$TLS_DIR"
  export REPSY_E2E_TLS_DIR
  # What a client would use without TLS; specs about the plain listeners read these (src/env.ts).
  REPSY_E2E_PLAIN_API_BASE_URL="http://localhost:$REPSY_E2E_API_PORT"
  REPSY_E2E_PLAIN_REPO_BASE_URL="http://localhost:$REPSY_E2E_REPO_PORT"
  export REPSY_E2E_PLAIN_API_BASE_URL REPSY_E2E_PLAIN_REPO_BASE_URL
  # The repo URL is also REPO_BASE_URL of the stack (the address npm and NuGet name in their URLs), so
  # "up" and "test" must agree on it. A URL the user set wins, as in derive_stack_env.
  if [ "${API_URL_EXPLICIT:-}" != "true" ]; then
    REPSY_API_BASE_URL="https://localhost:$REPSY_E2E_API_TLS_PORT"
  fi
  if [ "${REPO_URL_EXPLICIT:-}" != "true" ]; then
    REPSY_REPO_BASE_URL="https://localhost:$REPSY_E2E_REPO_TLS_PORT"
  fi
  export REPSY_API_BASE_URL REPSY_REPO_BASE_URL
}

# Fills TLS_RUN_ARGS with what "docker compose run" adds to a runner container of a TLS stack: the
# certificate directory and, for each client, the variable it reads its trusted CA from (README.md "TLS
# stack"). Done here rather than in docker-compose.runners.yml so that a new runner needs nothing, and
# so that a stack without the overlay sets none of them (an empty SSL_CERT_FILE would replace the system
# trust store). The Java truststore is for the JVM clients to be pointed at with their own flags.
TLS_RUN_ARGS=()
tls_run_args() {
  TLS_RUN_ARGS=()
  [ -n "$TLS_DIR" ] || return 0
  if [ ! -s "$TLS_DIR/ca.pem" ]; then
    echo "No certificate in $TLS_DIR: start the TLS stack first (./run.sh local up --tls, same project and offset)." >&2
    exit 1
  fi
  TLS_RUN_ARGS=(
    -v "$TLS_DIR:/tls:ro"
    -e SSL_CERT_FILE=/tls/ca.pem
    -e NODE_EXTRA_CA_CERTS=/tls/ca.pem
    -e REQUESTS_CA_BUNDLE=/tls/ca.pem
    -e CARGO_HTTP_CAINFO=/tls/ca.pem
    -e CURL_CA_BUNDLE=/tls/ca.pem
    -e REPSY_E2E_TLS_CA_FILE=/tls/ca.pem
    -e REPSY_E2E_TLS_TRUSTSTORE=/tls/truststore.p12
    -e REPSY_E2E_TLS_TRUSTSTORE_PASSWORD=changeit
    -e REPSY_E2E_PLAIN_API_BASE_URL
    -e REPSY_E2E_PLAIN_REPO_BASE_URL
  )
}

# Refuses to start or stop a stack that another checkout owns: a container of PROJECT running from a
# compose working directory other than this one, or a container of another project (or not from
# compose) publishing one of the host ports this stack needs. "local up" from a second worktree used
# to replace the first one's containers mid-run (RPS-1422). --force (REPSY_E2E_FORCE=1) skips it.
guard_stack_owner() {
  local action="$1"
  [ "$FORCE" = "true" ] && return 0
  local here_real
  here_real="$(pwd -P)"
  local dir
  while IFS= read -r dir; do
    if [ -z "$dir" ] || [ "$dir" = "$SCRIPT_DIR" ] || [ "$dir" = "$here_real" ]; then
      continue
    fi
    echo "Refusing to $action: the compose project \"$PROJECT\" is running from another checkout ($dir)," >&2
    echo "not from $SCRIPT_DIR. Use your own stack, e.g." >&2
    echo "  REPSY_E2E_PROJECT=<name> REPSY_E2E_PORT_OFFSET=<n> ./run.sh local $action" >&2
    echo "(and the same variables for \"test\" and \"sweep\"), or pass --force to take it over." >&2
    exit 1
  done < <(docker ps --filter "label=com.docker.compose.project=$PROJECT" \
    --format '{{.Label "com.docker.compose.project.working_dir"}}' | sort -u)
  [ "$action" = "up" ] || return 0

  local -a ports=("$REPSY_E2E_API_PORT" "$REPSY_E2E_REPO_PORT")
  overlay_active scanner && ports+=("$REPSY_E2E_SCANNER_PORT")
  overlay_active trivy && ports+=("$REPSY_E2E_SCANNER_PORT")
  overlay_active tls && ports+=("$REPSY_E2E_API_TLS_PORT" "$REPSY_E2E_REPO_TLS_PORT")
  local port line name project
  for port in "${ports[@]}"; do
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      name="${line%%|*}"
      project="${line#*|}"
      if [ "$project" != "$PROJECT" ]; then
        echo "Refusing to up: host port $port is published by the container \"$name\" (compose project \"${project:-none}\")." >&2
        echo "Pick another stack, e.g. REPSY_E2E_PROJECT=<name> REPSY_E2E_PORT_OFFSET=<n> ./run.sh local up, or pass --force." >&2
        exit 1
      fi
    done < <(docker ps --filter "publish=$port" --format '{{.Names}}|{{.Label "com.docker.compose.project"}}')
  done
}

# The "-f" arguments of the stack for the parsed flags: --h2 (or REPSY_E2E_STACK=h2) selects
# docker-compose.stack-h2.yml, anything else keeps the postgres profile; every active overlay adds its
# file. Fills STACK_ARGS.
STACK_ARGS=()
stack_args() {
  local row
  # -p overrides the "name:" of the files.
  if [ "$USE_H2" = "true" ]; then
    STACK_ARGS=(-p "$PROJECT" -f "$STACK_FILE_H2")
  else
    STACK_ARGS=(-p "$PROJECT" -f "$STACK_FILE")
  fi
  for row in "${OVERLAYS[@]}"; do
    if overlay_active "$(overlay_field "$row" 1)"; then
      STACK_ARGS+=(-f "$(overlay_field "$row" 4)")
    fi
  done
  # Two scanners cannot share the scanner port or the backend's scanner URL.
  if overlay_active scanner && overlay_active trivy; then
    echo "--scanner (the stub) and --trivy (the real scanner) are alternatives, pick one" >&2
    exit 1
  fi
}

require_admin_password() {
  if [ -z "${REPSY_ADMIN_PASSWORD:-}" ]; then
    echo "REPSY_ADMIN_PASSWORD is required. Set it in e2e/.env (copy .env.example)." >&2
    exit 1
  fi
}

# A short random lowercase-alnum id, generated the same way src/env.ts falls back to one, so a
# `test` run touching several protocol runners seeds all of them under one shared run id.
random_run_id() {
  local chars="abcdefghijklmnopqrstuvwxyz0123456789"
  local id="" r
  local -i i
  for ((i = 0; i < 6; i += 1)); do
    r=$((RANDOM % 36))
    id="${id}${chars:r:1}"
  done
  printf '%s' "$id"
}

# Every subcommand that starts a runner container (docker compose run) must call this first. The
# runners bind-mount ./test-results and ./playwright-report (docker-compose.runners.yml); when a
# directory is missing Docker creates it as root, and a later runner (which runs as the host
# uid:gid) then fails with EACCES writing its reports. Creating them here, as the invoking user,
# makes the bind mount reuse them.
ensure_runner_dirs() {
  mkdir -p test-results playwright-report
}

# The previous release's image for the upgrade overlay: the published image of the tag REPSY_E2E_UPGRADE_FROM,
# else of PREVIOUS_RELEASE in src/upgrade/previous-release.ts (the one place that names it, README.md "Upgrade
# path": bump it after each release).
upgrade_from_image() {
  local tag="${REPSY_E2E_UPGRADE_FROM:-}"
  if [ -z "$tag" ]; then
    tag="$(sed -n "s/^export const PREVIOUS_RELEASE = '\([^']*\)';.*/\1/p" src/upgrade/previous-release.ts)"
  fi
  if [ -z "$tag" ]; then
    echo "Cannot read PREVIOUS_RELEASE from src/upgrade/previous-release.ts" >&2
    exit 1
  fi
  printf 'repo.repsy.io/repsy/os/repsy:%s' "$tag"
}

# "local up --upgrade": the image under test is prepared but not started (REPSY_IMAGE as it is, or built
# under repsy-os-e2e:$REPSY_E2E_IMAGE_TAG), and the stack starts on the PREVIOUS release's image, on fresh
# volumes, with the overlay's old-style environment. tests/stack/upgrade.spec.ts recreates the container on
# the image under test (it reads REPSY_IMAGE, or that tag, too).
up_upgrade_stack() {
  local from_image target_image
  from_image="$(upgrade_from_image)"
  target_image="${REPSY_IMAGE:-repsy-os-e2e:$REPSY_E2E_IMAGE_TAG}"
  if [ -z "${REPSY_IMAGE:-}" ]; then
    # REPSY_IMAGE must not rename the build: the image under test keeps its own tag.
    docker compose "${STACK_ARGS[@]}" build repsy
  fi
  if ! docker pull "$from_image"; then
    echo "Cannot pull the previous release $from_image (no network, or the tag is not published): the upgrade leg needs it." >&2
    exit 1
  fi
  echo "Upgrade leg: starting $from_image; tests/stack/upgrade.spec.ts recreates it on $target_image"
  REPSY_IMAGE="$from_image" docker compose "${STACK_ARGS[@]}" up -d --wait --no-build
}

cmd_local_up() {
  parse_stack_flags up "$@"
  require_admin_password
  if overlay_active tls; then
    apply_tls_env
    mkdir -p "$TLS_DIR"
  fi
  stack_args
  guard_stack_owner up
  local db_label="postgres" overlay_label="" name
  [ "$USE_H2" = "true" ] && db_label="h2"
  for name in ${ACTIVE_OVERLAYS[@]+"${ACTIVE_OVERLAYS[@]}"}; do
    overlay_label="$overlay_label, $name overlay"
  done
  # `up` alone builds the Repsy image only when repsy-os-e2e:$REPSY_E2E_IMAGE_TAG does not exist yet, so a stale one
  # from an earlier checkout was reused and the runners tested old code (RPS-1321). Build every time
  # instead: Docker's layer cache makes it a near no-op when nothing under the build context
  # changed, and a changed source is never missed (an mtime check would miss e.g. a branch switch
  # or a core submodule bump). Not with REPSY_IMAGE: that names a published image to test as it is,
  # and `--build` would replace it with a local build under the same tag.
  if overlay_active upgrade; then
    up_upgrade_stack
  elif [ -n "${REPSY_IMAGE:-}" ]; then
    docker compose "${STACK_ARGS[@]}" up -d --wait
  else
    docker compose "${STACK_ARGS[@]}" up -d --wait --build
  fi
  echo "Repsy is up ($db_label$overlay_label, project $PROJECT): panel API on $REPSY_API_BASE_URL, repo protocols on $REPSY_REPO_BASE_URL"
  if overlay_active scanner; then
    echo "Stub scanner control API on http://localhost:$REPSY_E2E_SCANNER_PORT"
  fi
  if [ "$PROJECT" != "$DEFAULT_PROJECT" ] || [ "$PORT_OFFSET" -ne 0 ]; then
    echo "Give the same to test, sweep and down: REPSY_E2E_PROJECT=$PROJECT REPSY_E2E_PORT_OFFSET=$PORT_OFFSET ./run.sh ..."
  fi
  if overlay_active tls; then
    echo "TLS overlay on: https on $REPSY_API_BASE_URL and $REPSY_REPO_BASE_URL (plain http stays open); CA in $TLS_DIR/ca.pem; run REPSY_E2E_TLS=1 ./run.sh test --protocol skeleton,api,golang,docker,npm --grep @smoke (the ui runner is left out)"
  fi
  if overlay_active throttle; then
    echo "Throttle overlay on: 3 failed password checks per 10 s per client; run REPSY_E2E_THROTTLE=1 ./run.sh test --protocol stack,ui --grep @throttle (the ui runner last: AUTH-11 locks the docker gateway's bucket)"
  fi
  if overlay_active limits; then
    echo "Limits overlay on: uploads over 64 KiB are refused (413); run REPSY_E2E_LIMITS=1 ./run.sh test --protocol pypi,helm,nuget,ruby,cargo,golang,api --grep @limits, one runner per call"
  fi
  if overlay_active upgrade; then
    echo "Upgrade overlay on: run REPSY_E2E_UPGRADE=1 ./run.sh test --protocol stack --grep @upgrade (the stack ends on the image under test)"
  fi
  if overlay_active trivy; then
    echo "Real Trivy scanner on http://localhost:$REPSY_E2E_SCANNER_PORT: run REPSY_E2E_TRIVY=1 ./run.sh test --protocol api --grep @trivy (needs the network: Trivy downloads its vulnerability database)"
  fi
  if overlay_active scanner; then
    echo "Scanner enabled: run the @scanner specs with REPSY_UI_OPT_IN=scanner (or REPSY_E2E_SCANNER=1) ./run.sh test --protocol ui --grep @scanner"
  fi
}

cmd_local_down() {
  parse_stack_flags down "$@"
  overlay_active tls && apply_tls_env
  stack_args
  guard_stack_owner down
  # Compose interpolates the whole file for every command, "down" included, and the stack file
  # requires REPSY_ADMIN_PASSWORD. Tearing down does not use it, so any value will do.
  REPSY_ADMIN_PASSWORD="${REPSY_ADMIN_PASSWORD:-unused}" docker compose "${STACK_ARGS[@]}" down
}

# "local logs" and "local ps": read-only views of the stack "local up" started with the same flags. They
# take no ownership guard, since they change nothing. Like "down", compose needs some value for the
# required REPSY_ADMIN_PASSWORD to interpolate the stack file.
cmd_local_logs() {
  parse_stack_flags logs "$@"
  overlay_active tls && apply_tls_env
  stack_args
  REPSY_ADMIN_PASSWORD="${REPSY_ADMIN_PASSWORD:-unused}" docker compose "${STACK_ARGS[@]}" logs --no-color --timestamps
}

cmd_local_ps() {
  parse_stack_flags ps "$@"
  overlay_active tls && apply_tls_env
  stack_args
  REPSY_ADMIN_PASSWORD="${REPSY_ADMIN_PASSWORD:-unused}" docker compose "${STACK_ARGS[@]}" ps -a
}

cmd_test() {
  require_admin_password
  ensure_runner_dirs

  local target="local"
  local protocols=""
  local grep_pattern=""
  local rebuild="false"

  while [ $# -gt 0 ]; do
    case "$1" in
      --target)
        target="$2"
        shift 2
        ;;
      --protocol)
        protocols="$2"
        shift 2
        ;;
      --grep)
        grep_pattern="$2"
        shift 2
        ;;
      -b)
        rebuild="true"
        shift
        ;;
      *)
        echo "Unknown option for 'test': $1" >&2
        usage
        exit 1
        ;;
    esac
  done

  case "$target" in
    local | remote | ci) ;;
    *)
      echo "REPSY_TARGET must be one of local, remote, ci; got \"$target\"" >&2
      exit 1
      ;;
  esac
  export REPSY_TARGET="$target"

  # A stack started with an overlay (REPSY_E2E_SCANNER=1, REPSY_E2E_THROTTLE=1, ...) opts every runner into
  # that overlay's specs, which skip themselves otherwise: each active overlay's name joins
  # REPSY_E2E_OPT_IN (src/stack-overlays.ts optedIn(); docker-compose.runners.yml forwards it).
  local row switch
  for row in "${OVERLAYS[@]}"; do
    switch="$(overlay_field "$row" 3)"
    if env_switch_on "${!switch:-}"; then
      REPSY_E2E_OPT_IN="${REPSY_E2E_OPT_IN:+$REPSY_E2E_OPT_IN,}$(overlay_field "$row" 1)"
    fi
  done
  export REPSY_E2E_OPT_IN="${REPSY_E2E_OPT_IN:-}"
  env_switch_on "${REPSY_E2E_TLS:-}" && apply_tls_env
  tls_run_args

  if [ -z "${REPSY_E2E_RUN_ID:-}" ]; then
    REPSY_E2E_RUN_ID="$(random_run_id)"
    export REPSY_E2E_RUN_ID
  fi
  echo "Run id: $REPSY_E2E_RUN_ID"

  local -a services
  if [ -n "$protocols" ]; then
    IFS=',' read -ra services <<< "$protocols"
  else
    # No --protocol given: run the skeleton harness proof only. A protocol runner (maven, npm,
    # npm-clients, cargo, nuget, docker, helm, pypi, golang, ruby, api) is opt-in via --protocol so a plain
    # "run.sh test" stays fast; pass e.g. --protocol maven or
    # --protocol skeleton,maven,npm,npm-clients,cargo,nuget,docker,helm,pypi,golang,ruby,stack,ui to run more. "stack" is
    # the docker-exec cases against the container of a local stack (tests/stack); "ui" is the
    # panel UI suite (Playwright + headless Chromium, tests/ui), not a package format. "helm" runs
    # BOTH Helm protocols (OCI and classic/ChartMuseum, `tests/helm/*.spec.ts`) from one
    # runner/project.
    services=(skeleton)
  fi

  if [ "$rebuild" = "true" ]; then
    docker compose -p "$RUNNERS_PROJECT" -f "$RUNNERS_FILE" build "${services[@]}"
  fi

  local -a play_args=()
  if [ -n "$grep_pattern" ]; then
    play_args+=(--grep "$grep_pattern")
  fi

  local failed="false"
  local service
  for service in "${services[@]}"; do
    echo "==> Running $service"
    # entrypoint.sh regenerates the API client, then runs Playwright for the given project with
    # any extra args (e.g. --grep) appended.
    if ! docker compose -p "$RUNNERS_PROJECT" -f "$RUNNERS_FILE" run --rm ${TLS_RUN_ARGS[@]+"${TLS_RUN_ARGS[@]}"} "$service" \
      ./entrypoint.sh "$service" "${play_args[@]}"; then
      failed="true"
    fi
  done

  if [ "$failed" = "true" ]; then
    exit 1
  fi
}

cmd_sweep() {
  require_admin_password
  ensure_runner_dirs
  env_switch_on "${REPSY_E2E_TLS:-}" && apply_tls_env
  tls_run_args
  # Reuses the "skeleton" image: sweeping needs the harness and no protocol-specific tooling. Calls
  # tsx directly (see entrypoint.sh's comment: "pnpm exec" fails under the container's non-root,
  # host-matching uid because it re-verifies node_modules against a store built as root).
  docker compose -p "$RUNNERS_PROJECT" -f "$RUNNERS_FILE" run --rm ${TLS_RUN_ARGS[@]+"${TLS_RUN_ARGS[@]}"} skeleton ./node_modules/.bin/tsx src/seed/sweep.ts "$@"
}

main() {
  extract_global_options "$@"
  set -- ${REMAINING_ARGS[@]+"${REMAINING_ARGS[@]}"}
  derive_stack_env
  local group="${1:-}"

  case "$group" in
    local)
      shift || true
      case "${1:-}" in
        up)
          shift || true
          cmd_local_up "$@"
          ;;
        down)
          shift || true
          cmd_local_down "$@"
          ;;
        logs)
          shift || true
          cmd_local_logs "$@"
          ;;
        ps)
          shift || true
          cmd_local_ps "$@"
          ;;
        *)
          usage
          exit 1
          ;;
      esac
      ;;
    test)
      shift || true
      cmd_test "$@"
      ;;
    sweep)
      shift || true
      cmd_sweep "$@"
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
