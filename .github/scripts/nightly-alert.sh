#!/usr/bin/env bash
# Alerts on a red scheduled run, or closes the alert when the run is green (RPS-2728).
#
# Called by the last job of a scheduled workflow, after every other job has finished. It looks at the
# jobs of the current run and then:
#   - red:   posts to Slack (only when SLACK_WEBHOOK_URL is set) and opens, or comments on, the ONE open
#            issue "Nightly red: <workflow>" with the label `nightly-red`;
#   - green: comments on that issue and closes it.
#
# Env:   GH_TOKEN           token for `gh` (actions: read, issues: write)
#        GITHUB_REPOSITORY  owner/name
#        RUN_ID, RUN_URL    the run being judged
#        WORKFLOW_NAME      name of the workflow
#        NOTIFY_JOB_NAME    name of the job running this script (left out of the verdict)
#        ALERT_TEST         `true` for a deliberate test run: the issue gets a different title and Slack does not @channel
#        SLACK_WEBHOOK_URL  optional; never printed
set -u

LABEL="nightly-red"
TITLE="Nightly red: ${WORKFLOW_NAME}"
if [ "${ALERT_TEST:-}" = "true" ]; then
  TITLE="Nightly red (alert test): ${WORKFLOW_NAME}"
fi

if ! jobs_tsv="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}/jobs?per_page=100" \
  --jq '.jobs[] | [.name, .conclusion] | @tsv')"; then
  echo "::error::Could not read the jobs of run ${RUN_ID}"
  exit 1
fi

failed="$(printf '%s\n' "$jobs_tsv" | awk -F'\t' -v me="${NOTIFY_JOB_NAME:-}" \
  '$1 != me && ($2 == "failure" || $2 == "timed_out") { print "- " $1 " (" $2 ")" }')"
cancelled="$(printf '%s\n' "$jobs_tsv" | awk -F'\t' -v me="${NOTIFY_JOB_NAME:-}" '$1 != me && $2 == "cancelled"' | wc -l)"
succeeded="$(printf '%s\n' "$jobs_tsv" | awk -F'\t' -v me="${NOTIFY_JOB_NAME:-}" '$1 != me && $2 == "success"' | wc -l)"

find_issue() {
  gh issue list -R "$GITHUB_REPOSITORY" --label "$LABEL" --state open --limit 100 --json number,title \
    --jq "map(select(.title == \"${TITLE}\")) | .[0].number // empty"
}

if [ -n "$failed" ]; then
  echo "Run ${RUN_ID} is red:"
  printf '%s\n' "$failed"

  if [ -n "${SLACK_WEBHOOK_URL:-}" ]; then
    mention="<!channel> "
    prefix=""
    if [ "${ALERT_TEST:-}" = "true" ]; then mention=""; prefix="(alert test) "; fi
    text="$(printf '%s%s%s is red\n%s\n%s' "$mention" "$prefix" "$WORKFLOW_NAME" "$failed" "$RUN_URL")"
    if ! jq -n --arg text "$text" '{attachments: [{color: "#ff0000", text: $text}]}' \
      | curl -sS --fail -X POST -H 'Content-type: application/json' --data @- "$SLACK_WEBHOOK_URL" >/dev/null; then
      echo "::warning::The Slack post failed; the issue is still updated"
    fi
  else
    echo "SLACK_WEBHOOK_URL is not set: skipping Slack, the issue is the alert"
  fi

  gh label create "$LABEL" -R "$GITHUB_REPOSITORY" --color B60205 \
    --description "A scheduled workflow is red" >/dev/null 2>&1 || true
  body="$(printf 'Run: %s\n\nFailing jobs:\n%s\n' "$RUN_URL" "$failed")"
  number="$(find_issue)" || { echo "::error::Could not list issues"; exit 1; }
  if [ -n "$number" ]; then
    gh issue comment "$number" -R "$GITHUB_REPOSITORY" --body "Still red.

${body}"
  else
    gh issue create -R "$GITHUB_REPOSITORY" --title "$TITLE" --label "$LABEL" --body "${body}

The issue closes by itself when a scheduled run of this workflow is green again."
  fi
elif [ "$cancelled" -eq 0 ] && [ "$succeeded" -gt 0 ]; then
  echo "Run ${RUN_ID} is green"
  number="$(find_issue)" || { echo "::error::Could not list issues"; exit 1; }
  if [ -n "$number" ]; then
    gh issue close "$number" -R "$GITHUB_REPOSITORY" --comment "Green again: ${RUN_URL}"
  fi
else
  echo "Run ${RUN_ID} was cancelled or ran nothing: leaving the alert as it is"
fi
