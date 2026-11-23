#!/usr/bin/env bash
# Shows the state of the newest completed scheduled run of each given workflow on main, so a release
# can see whether the nightlies are red (RPS-2740).
#
# Usage: nightly-state.sh <workflow-file>...
# Env:   GH_TOKEN          token for `gh` (needs actions: read)
#        GITHUB_REPOSITORY owner/name (default: the repo of the current checkout)
#        GITHUB_SHA        the commit being released (default: HEAD)
#        NIGHTLY_GATE      `warn` (default) never fails; `block` exits 1 on a red or unknown workflow
#        STALE_HOURS       a run older than this is `unknown` (default 36)
# Fails open: an API or parsing error is a warning, never a failed release.
set -u

GATE="${NIGHTLY_GATE:-warn}"
STALE_HOURS="${STALE_HOURS:-36}"
REPO="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD 2>/dev/null || true)}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"
bad=0

if [ "$#" -eq 0 ] || [ -z "$REPO" ]; then
  echo "::warning::nightly-state: no workflow given or repository unknown, nothing checked"
  exit 0
fi

{
  echo "### Nightly state before this release"
  echo
  echo "Gate: \`$GATE\`. Release commit: \`${SHA:0:7}\`."
  echo
  echo "| Workflow | State | Run date (UTC) | Run commit | Not covered | Run |"
  echo "| --- | --- | --- | --- | --- | --- |"
} >> "$SUMMARY"

row() { # name state date sha uncovered url
  echo "| $1 | $2 | $3 | $4 | $5 | $6 |" >> "$SUMMARY"
  echo "$1: $2, run $3 $4, $5 ${6:+($6)}"
}

for wf in "$@"; do
  if ! out="$(gh api "repos/$REPO/actions/workflows/$wf/runs?event=schedule&branch=main&status=completed&per_page=1" \
      --jq '.workflow_runs[0] // empty | [.name, .conclusion, .created_at, .head_sha, .html_url] | @tsv' 2>&1)"; then
    echo "::warning::nightly-state: could not read the runs of $wf ($(echo "$out" | tr "\n" " " | cut -c1-120)); state unknown"
    row "$wf" "unknown (API error)" "-" "-" "-" ""
    bad=1
    continue
  fi
  if [ -z "$out" ]; then
    echo "::warning::nightly-state: $wf has no completed scheduled run on main; state unknown"
    row "$wf" "unknown (no run)" "-" "-" "-" ""
    bad=1
    continue
  fi
  IFS=$'\t' read -r name conclusion created head url <<< "$out"
  state="$conclusion"
  now="$(date -u +%s)"
  then_s="$(date -u -d "$created" +%s 2>/dev/null || echo "$now")"
  age_h=$(( (now - then_s) / 3600 ))
  if [ "$age_h" -gt "$STALE_HOURS" ]; then
    state="unknown (last run ${age_h}h ago)"
  fi

  uncovered="-"
  if [ -n "$SHA" ]; then
    if ahead="$(gh api "repos/$REPO/compare/$head...$SHA" --jq .ahead_by 2>/dev/null)" && [ -n "$ahead" ]; then
      uncovered="$ahead commits"
    else
      uncovered="? (compare failed)"
    fi
  fi

  row "$name" "$state" "$created" "${head:0:7}" "$uncovered" "$url"
  if [ "$conclusion" != "success" ] || [ "$state" != "$conclusion" ]; then
    msg="nightly-state: $name is $state on its last scheduled run ($url)"
    case "$uncovered" in
      "-"|"?"*|"0 commits") ;;
      *) msg="$msg; ${uncovered%% *} commits in this release were not covered by that run" ;;
    esac
    echo "::warning::$msg"
    bad=1
  fi
done

if [ "$bad" -ne 0 ] && [ "$GATE" = "block" ]; then
  echo "::error::nightly-state: NIGHTLY_GATE=block and at least one nightly is red or unknown"
  exit 1
fi
exit 0
