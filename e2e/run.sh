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

usage() {
  cat <<'EOF'
Usage:
  run.sh local up|down
  run.sh test [--target local|remote|ci] [--protocol a,b] [--grep PATTERN] [-b]
  run.sh sweep [--hours N] [--all] [--dry-run]

REPSY_ADMIN_PASSWORD must be set (copy .env.example to .env and fill it in) for every subcommand
except "local down".
EOF
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

cmd_local_up() {
  require_admin_password
  docker compose -f "$STACK_FILE" up -d --wait
  echo "Repsy is up: panel API on http://localhost:8080, repo protocols on http://localhost:9090"
}

cmd_local_down() {
  # Compose interpolates the whole file for every command, "down" included, and the stack file
  # requires REPSY_ADMIN_PASSWORD. Tearing down does not use it, so any value will do.
  REPSY_ADMIN_PASSWORD="${REPSY_ADMIN_PASSWORD:-unused}" docker compose -f "$STACK_FILE" down
}

cmd_test() {
  require_admin_password
  # Created here, as the host user, so the bind mount in docker-compose.runners.yml reuses this
  # directory instead of Docker auto-creating it as root on first use.
  mkdir -p test-results playwright-report

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
    # cargo, and more from later steps) is opt-in via --protocol so a plain "run.sh test" stays
    # fast; pass e.g. --protocol maven or --protocol skeleton,maven,npm,cargo to run more.
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
          cmd_local_up
          ;;
        down)
          cmd_local_down
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
