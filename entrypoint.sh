#!/bin/sh
set -eu

: "${API_BASE_URL:=}"
: "${REPO_BASE_URL:=http://localhost:9090}"

STATIC_ENV_FILE="./static/assets/static-env.js"

if [ -f "$STATIC_ENV_FILE" ]; then
  sed -i \
    -e "s|__API_BASE_URL__|${API_BASE_URL}|g" \
    -e "s|__REPO_BASE_URL__|${REPO_BASE_URL}|g" \
    "$STATIC_ENV_FILE"
fi

exec java -jar app.jar
