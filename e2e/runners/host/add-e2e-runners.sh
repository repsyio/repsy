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

# Adds ephemeral GitHub Actions runner containers with another label next to the ones of the runner host
# (RPS-1982): by default `hetzner-e2e-1..3`, label `hetzner-e2e,hetzner`, cloned from `hetzner-md-1`.
#
# Run it on the runner host as a user who may use its Docker daemon, by hand:
#
#   ./add-e2e-runners.sh                 # dry run: prints the plan, creates nothing
#   ./add-e2e-runners.sh --apply         # creates the containers that do not exist yet
#
# The new containers take everything from the template container: image, restart policy, CPU / memory / pids
# limits, mounts, entrypoint, command and environment. Only the name, the label and the work directory change
# (every `<template>` in a value becomes the new name, every `<from>` the new label; the credentials are
# copied as they are). The environment holds the runner registration credentials (a GitHub App id and its
# private key, which is several lines long): the script never prints, logs or writes a value of it. It hands
# the values to `docker run` through the environment of the docker client (`-e NAME` without a value), so
# they appear neither in the arguments nor on disk. The dry run prints the NAMES of the variables only.
#
# Roll back with: docker rm -f hetzner-e2e-1 hetzner-e2e-2 hetzner-e2e-3
# Nothing here changes the existing runners. Set the E2E_RUNNER variable only after the new runners show up as
# online in the organisation (a label without a runner queues its jobs forever).
set -euo pipefail

TEMPLATE="hetzner-md-1"
FROM_LABEL="hetzner-md"
TO_LABEL="hetzner-e2e"
COUNT=3
APPLY="false"

usage() {
  sed -n '/^# Adds/,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --template) TEMPLATE="$2"; shift 2 ;;
    --from) FROM_LABEL="$2"; shift 2 ;;
    --to) TO_LABEL="$2"; shift 2 ;;
    --count) COUNT="$2"; shift 2 ;;
    --apply) APPLY="true"; shift ;;
    -h | --help) usage 0 ;;
    *) echo "unknown argument: $1" >&2; usage 1 ;;
  esac
done

if ! docker inspect "$TEMPLATE" > /dev/null 2>&1; then
  echo "template container $TEMPLATE not found" >&2
  exit 1
fi

TEMPLATE="$TEMPLATE" FROM_LABEL="$FROM_LABEL" TO_LABEL="$TO_LABEL" COUNT="$COUNT" APPLY="$APPLY" python3 - << 'PY'
import json
import os
import re
import subprocess
import sys

template = os.environ["TEMPLATE"]
from_label = os.environ["FROM_LABEL"]
to_label = os.environ["TO_LABEL"]
count = int(os.environ["COUNT"])
apply = os.environ["APPLY"] == "true"
# Variables whose value is never rewritten (credentials) and never printed. Everything else is printed only when it
# is on the allow list below.
SECRET = re.compile(r"(KEY|SECRET|TOKEN|PASSWORD)", re.I)
PRINTABLE = {"LABELS", "RUNNER_WORKDIR", "EPHEMERAL"}


def docker(*args, env=None, check=True):
    return subprocess.run(["docker", *args], env=env, check=check, capture_output=True, text=True)


info = json.loads(docker("inspect", template).stdout)[0]
config, host = info["Config"], info["HostConfig"]
template_env = {}
for entry in config.get("Env") or []:
    name, _, value = entry.partition("=")
    template_env[name] = value
if "APP_ID" not in template_env or not any(SECRET.search(n) for n in template_env):
    sys.exit("the template has no runner credentials in its environment: refusing to clone it")


def rename(value, new_name):
    value = value.replace(template, new_name)
    return re.sub(r"(?<![A-Za-z0-9-])" + re.escape(from_label) + r"(?![A-Za-z0-9-])", to_label, value)


def plan(index):
    new_name = f"{to_label}-{index}"
    env = {n: (v if SECRET.search(n) else rename(v, new_name)) for n, v in template_env.items()}
    mounts = []
    for m in info.get("Mounts") or []:
        if m["Type"] == "volume" and re.fullmatch(r"[0-9a-f]{64}", m.get("Name", "")):
            continue  # an anonymous volume of the image: docker creates one for each new container
        if m["Type"] != "bind":
            sys.exit(f"mount {m['Destination']} is a {m['Type']} mount; only bind mounts are supported")
        mounts.append((rename(m["Source"], new_name), rename(m["Destination"], new_name), m.get("RW", True), m["Source"]))
    return new_name, env, mounts


print(f"template   {template}  ({config['Image']})")
print(f"clone as   {to_label}-1..{count}  (label {from_label} -> {to_label}, name {template} -> new name)")
limits = []
if host.get("NanoCpus"):
    limits.append(f"cpus={host['NanoCpus'] / 1e9:g}")
if host.get("Memory"):
    limits.append(f"memory={host['Memory'] // (1 << 20)}m")
if host.get("PidsLimit"):
    limits.append(f"pids={host['PidsLimit']}")
if host.get("CpuShares"):
    limits.append(f"cpu-shares={host['CpuShares']}")
print(f"limits     {' '.join(limits) or 'none'}  restart={host['RestartPolicy']['Name'] or 'no'}")
print(f"variables  {', '.join(sorted(template_env))}  (values are not printed)")

for index in range(1, count + 1):
    new_name, env, mounts = plan(index)
    print(f"\n[{new_name}]")
    for name in sorted(PRINTABLE & set(env)):
        print(f"  {name}={env[name]}")
    for source, destination, rw, _ in mounts:
        print(f"  mount {source} -> {destination}{'' if rw else ' (ro)'}")
    if docker("inspect", new_name, check=False).returncode == 0:
        print("  exists already: skipped")
        continue
    if not apply:
        print("  dry run: not created")
        continue
    for source, _, _, template_source in mounts:
        if source != template_source and not os.path.exists(source):
            # a directory of its own for this runner (the work dir): same owner as the template's
            os.makedirs(source)
            stat = os.stat(template_source)
            os.chown(source, stat.st_uid, stat.st_gid)
    args = ["run", "-d", "--name", new_name, "--restart", host["RestartPolicy"]["Name"] or "no"]
    if host.get("NanoCpus"):
        args += ["--cpus", f"{host['NanoCpus'] / 1e9:g}"]
    if host.get("Memory"):
        args += ["--memory", str(host["Memory"])]
    if host.get("PidsLimit"):
        args += ["--pids-limit", str(host["PidsLimit"])]
    if host.get("CpuShares"):
        args += ["--cpu-shares", str(host["CpuShares"])]
    for source, destination, rw, _ in mounts:
        args += ["-v", f"{source}:{destination}" + ("" if rw else ":ro")]
    for name in env:
        args += ["-e", name]  # no value: docker reads it from the client's environment, so it is never in an argument
    entrypoint = config.get("Entrypoint") or []
    if entrypoint:
        args += ["--entrypoint", entrypoint[0]]
    args += [config["Image"], *entrypoint[1:], *(config.get("Cmd") or [])]
    result = docker(*args, env={**os.environ, **env}, check=False)
    if result.returncode != 0:
        # docker's own message names the problem; it never contains the values it was given
        sys.exit(f"{new_name}: docker run failed: {result.stderr.strip()[:300]}")
    print(f"  created {result.stdout.strip()[:12]}")

if not apply:
    print("\ndry run: nothing was created; add --apply")
PY
