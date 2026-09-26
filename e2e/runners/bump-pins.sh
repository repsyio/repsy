#!/usr/bin/env bash
#
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

# RPS-1597: keeps the content pins of the runner images (README.md "Runner images and pins") in step with
# the versions docker-compose.runners.yml names.
#
#   runners/bump-pins.sh            resolve every pin for the CURRENT versions and print what differs from
#                                   the file (exit 1 if anything does; nothing is modified)
#   runners/bump-pins.sh --write    the same, and rewrite the differing values in docker-compose.runners.yml
#
# The procedure to bump a client: edit its version in docker-compose.runners.yml (the *_VERSION arg), run
# this script with --write, read the diff (`git diff`), rebuild the runner (`./run.sh test --protocol <p> -b
# --grep @smoke`) and commit both. With no version change it answers "has a tag I pin been moved or an
# image re-published?": a drifted digest is reported, never silently taken.
#
# What it resolves, per pin (all values are ARGs of docker-compose.runners.yml `build.args`, the single
# source; the Dockerfiles carry no default for them):
#   *_IMAGE_DIGEST   the manifest-LIST digest of the image the Dockerfile's `FROM <image>@${*_IMAGE_DIGEST}`
#                    names, with the ${...} of that image reference filled from the same args
#                    (`docker buildx imagetools inspect`; Dependabot cannot update an ARG-interpolated FROM)
#   SKOPEO_COMMIT    the commit the tag $SKOPEO_VERSION points at (`git ls-remote <tag>^{}`)
#   *_SHA256/_SHA512 the checksum the publisher lists next to the release file (regctl publishes none:
#                    its binary is downloaded and hashed, so the value is what a reviewer must look at)
# A client that is not listed here is not pinned by a checksum (README.md names them: the npm-installed
# clients and the helm cm-push plugin). A pin this script has no resolver for is an error, so a new one
# cannot be added without a way to keep it fresh.
#
# Needs bash 4, curl, git, awk, sed, sha256sum and docker with buildx. It talks to public registries and
# hosts only; it does not need a running stack.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

COMPOSE="docker-compose.runners.yml"
WRITE=0
case "${1:-}" in
  "") ;;
  --write) WRITE=1 ;;
  *)
    echo "usage: runners/bump-pins.sh [--write]" >&2
    exit 2
    ;;
esac

declare -A VALUE   # arg name -> value (the same everywhere it is repeated)
declare -A AT      # arg name -> the line numbers it sits on, space separated
status=0

# The build args are the only lines indented by exactly eight spaces with a quoted value.
while IFS=$'\t' read -r line key value; do
  if [[ -n "${VALUE[$key]+set}" && "${VALUE[$key]}" != "$value" ]]; then
    echo "CONFLICT ${key}: '${VALUE[$key]}' and '${value}' (line ${line}); a value that is repeated across services must be equal" >&2
    status=2
  fi
  VALUE[$key]="$value"
  AT[$key]="${AT[$key]:-} ${line}"
done < <(awk -F"'" '/^        [A-Z][A-Z0-9_]*: '"'"'.*'"'"'$/ { key = $1; sub(/^ +/, "", key); sub(/:.*/, "", key); printf "%d\t%s\t%s\n", NR, key, $2 }' "$COMPOSE")

# Fills every ${NAME} of a string from the compose args.
expand() {
  local s="$1" name
  while [[ "$s" =~ \$\{([A-Z0-9_]+)\} ]]; do
    name="${BASH_REMATCH[1]}"
    if [[ -z "${VALUE[$name]+set}" ]]; then
      echo "no compose arg ${name} for ${1}" >&2
      return 1
    fi
    s="${s//\$\{${name}\}/${VALUE[$name]}}"
  done
  printf '%s' "$s"
}

# The image behind every *_IMAGE_DIGEST arg, read from the Dockerfiles' `FROM <image>@${ARG} AS <stage>`.
declare -A IMAGE
while IFS= read -r from; do
  ref="${from#FROM }"
  ref="${ref%% AS *}"
  key="${ref##*@\$\{}"
  key="${key%\}}"
  image="$(expand "${ref%@*}")" || { status=2; continue; }
  if [[ -n "${IMAGE[$key]+set}" && "${IMAGE[$key]}" != "$image" ]]; then
    echo "CONFLICT ${key}: two Dockerfiles pin different images (${IMAGE[$key]} and ${image}) with one digest arg" >&2
    status=2
  fi
  IMAGE[$key]="$image"
done < <(grep -h -E '^FROM [^ ]+@\$\{[A-Z0-9_]+_IMAGE_DIGEST\}' runners/*.Dockerfile)

fetch() { curl -fsSL --retry 3 --retry-delay 2 "$1"; }

# The first field of the first line: `<checksum>  <file>` and a bare checksum both work (archive.apache.org
# serves some of them with CRLF line ends).
first_word() { tr -d '\r' | awk 'NR == 1 { print $1 }'; }

# regctl and oras name a release file per architecture.
arch_of() { case "$1" in *_AMD64) echo amd64 ;; *_ARM64) echo arm64 ;; *) return 1 ;; esac; }

resolve() {
  local key="$1" v arch
  case "$key" in
    *_IMAGE_DIGEST)
      [[ -n "${IMAGE[$key]+set}" ]] || { echo "no Dockerfile has FROM <image>@\${${key}}" >&2; return 1; }
      docker buildx imagetools inspect "${IMAGE[$key]}" --format '{{.Manifest.Digest}}'
      ;;
    SKOPEO_COMMIT)
      git ls-remote https://github.com/containers/skopeo "refs/tags/${VALUE[SKOPEO_VERSION]}^{}" | awk 'NR == 1 { print $1 }'
      ;;
    TEMURIN_SHA256)
      # Adoptium's release `jdk-21.0.12.1+1` names its x64 tarball OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz;
      # the checksum file next to it is the `package.checksum` api.adoptium.net/v3/assets reports.
      v="${VALUE[TEMURIN_VERSION]}"
      fetch "https://github.com/adoptium/temurin${v%%.*}-binaries/releases/download/jdk-${v//+/%2B}/OpenJDK${v%%.*}U-jdk_x64_linux_hotspot_${v//+/_}.tar.gz.sha256.txt" | first_word
      ;;
    MAVEN_SHA512)
      v="${VALUE[MAVEN_VERSION]}"
      fetch "https://archive.apache.org/dist/maven/maven-${v%%.*}/${v}/binaries/apache-maven-${v}-bin.tar.gz.sha512" | first_word
      ;;
    GRADLE_SHA256)
      fetch "https://services.gradle.org/distributions/gradle-${VALUE[GRADLE_VERSION]}-bin.zip.sha256" | first_word
      ;;
    SBT_SHA256)
      v="${VALUE[SBT_VERSION]}"
      fetch "https://github.com/sbt/sbt/releases/download/v${v}/sbt-${v}.tgz.sha256" | first_word
      ;;
    ANT_SHA512)
      v="${VALUE[ANT_VERSION]}"
      fetch "https://archive.apache.org/dist/ant/binaries/apache-ant-${v}-bin.tar.gz.sha512" | first_word
      ;;
    IVY_SHA512)
      v="${VALUE[IVY_VERSION]}"
      fetch "https://archive.apache.org/dist/ant/ivy/${v}/apache-ivy-${v}-bin.tar.gz.sha512" | first_word
      ;;
    HELM_SHA256_*)
      arch="$(arch_of "$key")"
      fetch "https://get.helm.sh/helm-${VALUE[HELM_VERSION]}-linux-${arch}.tar.gz.sha256sum" | first_word
      ;;
    ORAS_SHA256_*)
      arch="$(arch_of "$key")"
      v="${VALUE[ORAS_VERSION]}"
      fetch "https://github.com/oras-project/oras/releases/download/${v}/oras_${v#v}_checksums.txt" \
        | tr -d '\r' | awk -v f="oras_${v#v}_linux_${arch}.tar.gz" '$2 == f { print $1 }'
      ;;
    REGCTL_SHA256_*)
      arch="$(arch_of "$key")"
      fetch "https://github.com/regclient/regclient/releases/download/${VALUE[REGCTL_VERSION]}/regctl-linux-${arch}" | sha256sum | first_word
      ;;
    *)
      echo "no resolver for ${key}: add one to runners/bump-pins.sh, or it can never be refreshed" >&2
      return 1
      ;;
  esac
}

changed=0
for key in $(printf '%s\n' "${!VALUE[@]}" | grep -E '_IMAGE_DIGEST$|_COMMIT$|_SHA256(_|$)|_SHA512$' | sort); do
  if ! resolved="$(resolve "$key")" || [[ -z "$resolved" ]]; then
    echo "ERROR    ${key}: could not be resolved" >&2
    status=2
    continue
  fi
  if [[ "$resolved" == "${VALUE[$key]}" ]]; then
    echo "ok       ${key}"
    continue
  fi
  changed=1
  echo "DIFFERS  ${key}"
  echo "           file:     ${VALUE[$key]}"
  echo "           resolved: ${resolved}"
  if ((WRITE)); then
    for ln in ${AT[$key]}; do
      sed -i "${ln}s/: '[^']*'\$/: '${resolved}'/" "$COMPOSE"
    done
  fi
done

# Every digest arg a Dockerfile uses must exist in the compose file (a build without it fails, loudly, but
# only for that runner: say so here, for all of them).
for key in "${!IMAGE[@]}"; do
  if [[ -z "${VALUE[$key]+set}" ]]; then
    echo "ERROR    ${key}: used by a Dockerfile but not set in ${COMPOSE}" >&2
    status=2
  fi
done

if ((status != 0)); then
  exit "$status"
fi
if ((changed && !WRITE)); then
  echo "Some pins differ: re-run with --write to update ${COMPOSE}, then review the diff." >&2
  exit 1
fi
if ((changed)); then
  echo "Updated ${COMPOSE}; review 'git diff' and rebuild the affected runners."
fi
