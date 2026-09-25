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
# Overlay for either stack file: adds the stub scanner and points Repsy at it (README.md "Scanner stack").
STACK_FILE_SCANNER="docker-compose.stack-scanner.yml"
RUNNERS_FILE="docker-compose.runners.yml"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

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
  run.sh local up|down [--h2] [--scanner]
  run.sh test [--target local|remote|ci] [--protocol a,b] [--grep PATTERN] [-b]
  run.sh sweep [--hours N] [--all] [--dry-run]

--protocol takes runner service names: skeleton, maven, npm, npm-clients (the npm registry under pnpm,
yarn classic, yarn berry and bun as well as npm; see README.md "npm-family clients"), cargo, nuget,
docker, helm, pypi, golang, ruby, stack (cases that docker-exec into the Repsy container, tests/stack;
local stack only, see README.md "Stack runner") and ui (the panel UI suite in headless Chromium,
tests/ui; see README.md "UI suite").

REPSY_ADMIN_PASSWORD must be set (copy .env.example to .env and fill it in) for every subcommand
except "local down".

"local up|down" starts/stops the postgres profile by default (docker-compose.stack.yml). Pass
--h2, or set REPSY_E2E_STACK=h2, to use the embedded-H2 profile (docker-compose.stack-h2.yml)
instead -- same ports/image, no postgres service, a fresh H2 database on every "up". "run.sh test"
needs no flag either way: both profiles serve the same REPSY_API_BASE_URL/REPSY_REPO_BASE_URL, so
every runner is unchanged.

Pass --scanner, or set REPSY_E2E_SCANNER=1, to add the opt-in stub scanner (docker-compose.stack-scanner.yml,
combinable with --h2): Repsy starts with SECURITY_SCANNER=enabled pointed at a deterministic stand-in for
repsy-scanner-trivy, which the @scanner UI specs need (REPSY_UI_OPT_IN=scanner; with REPSY_E2E_SCANNER=1
"run.sh test" adds that opt-in itself). The default stack never starts a scanner. Give "down" the same
flags as "up". See README.md "Scanner stack".
EOF
}

# True when an env switch such as REPSY_E2E_SCANNER is set to something other than off.
env_switch_on() {
  case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
    '' | 0 | false | no | off) return 1 ;;
    *) return 0 ;;
  esac
}

# Parses the flags of "local up|down" into USE_H2 and USE_SCANNER ("true"/"false"). Both can also be
# switched on from the environment: REPSY_E2E_STACK=h2 and REPSY_E2E_SCANNER=1.
USE_H2="false"
USE_SCANNER="false"
parse_stack_flags() {
  local sub="$1"
  shift
  USE_H2="false"
  USE_SCANNER="false"
  local arg
  for arg in "$@"; do
    case "$arg" in
      --h2) USE_H2="true" ;;
      --scanner) USE_SCANNER="true" ;;
      *)
        echo "Unknown option for 'local $sub': $arg" >&2
        usage
        exit 1
        ;;
    esac
  done
  [ "${REPSY_E2E_STACK:-}" = "h2" ] && USE_H2="true"
  env_switch_on "${REPSY_E2E_SCANNER:-}" && USE_SCANNER="true"
  return 0
}

# The "-f" arguments of the stack for the parsed flags: --h2 (or REPSY_E2E_STACK=h2) selects
# docker-compose.stack-h2.yml, anything else keeps the postgres profile; --scanner adds the overlay.
# Fills STACK_ARGS.
STACK_ARGS=()
stack_args() {
  if [ "$USE_H2" = "true" ]; then
    STACK_ARGS=(-f "$STACK_FILE_H2")
  else
    STACK_ARGS=(-f "$STACK_FILE")
  fi
  if [ "$USE_SCANNER" = "true" ]; then
    STACK_ARGS+=(-f "$STACK_FILE_SCANNER")
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

cmd_local_up() {
  parse_stack_flags up "$@"
  require_admin_password
  stack_args
  local db_label="postgres" scanner_label=""
  [ "$USE_H2" = "true" ] && db_label="h2"
  [ "$USE_SCANNER" = "true" ] && scanner_label=", stub scanner"
  # `up` alone builds the Repsy image only when repsy-os-e2e:local does not exist yet, so a stale one
  # from an earlier checkout was reused and the runners tested old code (RPS-1321). Build every time
  # instead: Docker's layer cache makes it a near no-op when nothing under the build context
  # changed, and a changed source is never missed (an mtime check would miss e.g. a branch switch
  # or a core submodule bump). Not with REPSY_IMAGE: that names a published image to test as it is,
  # and `--build` would replace it with a local build under the same tag.
  if [ -n "${REPSY_IMAGE:-}" ]; then
    docker compose "${STACK_ARGS[@]}" up -d --wait
  else
    docker compose "${STACK_ARGS[@]}" up -d --wait --build
  fi
  echo "Repsy is up ($db_label$scanner_label): panel API on http://localhost:8080, repo protocols on http://localhost:9090"
  if [ "$USE_SCANNER" = "true" ]; then
    echo "Scanner enabled: run the @scanner specs with REPSY_UI_OPT_IN=scanner (or REPSY_E2E_SCANNER=1) ./run.sh test --protocol ui --grep @scanner"
  fi
}

cmd_local_down() {
  parse_stack_flags down "$@"
  stack_args
  # Compose interpolates the whole file for every command, "down" included, and the stack file
  # requires REPSY_ADMIN_PASSWORD. Tearing down does not use it, so any value will do.
  REPSY_ADMIN_PASSWORD="${REPSY_ADMIN_PASSWORD:-unused}" docker compose "${STACK_ARGS[@]}" down
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

  # A stack started with the scanner overlay (REPSY_E2E_SCANNER=1) also opts the UI suite into its
  # @scanner specs, which skip themselves otherwise (src/ui/session.ts optedIn()).
  if env_switch_on "${REPSY_E2E_SCANNER:-}"; then
    export REPSY_UI_OPT_IN="${REPSY_UI_OPT_IN:+$REPSY_UI_OPT_IN,}scanner"
  fi

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
    # npm-clients, cargo, nuget, docker, helm, pypi, golang, ruby) is opt-in via --protocol so a plain
    # "run.sh test" stays fast; pass e.g. --protocol maven or
    # --protocol skeleton,maven,npm,npm-clients,cargo,nuget,docker,helm,pypi,golang,ruby,stack,ui to run more. "stack" is
    # the docker-exec cases against the container of a local stack (tests/stack); "ui" is the
    # panel UI suite (Playwright + headless Chromium, tests/ui), not a package format. "helm" runs
    # BOTH Helm protocols (OCI and classic/ChartMuseum, `tests/helm/*.spec.ts`) from one
    # runner/project.
    services=(skeleton)
  fi

  if [ "$rebuild" = "true" ]; then
    docker compose -f "$RUNNERS_FILE" build "${services[@]}"
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
    if ! docker compose -f "$RUNNERS_FILE" run --rm "$service" \
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
  # Reuses the "skeleton" image: sweeping needs the harness and no protocol-specific tooling. Calls
  # tsx directly (see entrypoint.sh's comment: "pnpm exec" fails under the container's non-root,
  # host-matching uid because it re-verifies node_modules against a store built as root).
  docker compose -f "$RUNNERS_FILE" run --rm skeleton ./node_modules/.bin/tsx src/seed/sweep.ts "$@"
}

main() {
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
