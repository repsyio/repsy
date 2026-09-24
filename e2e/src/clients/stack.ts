///
/// Copyright 2026 the original author or authors.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///      https://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

/**
 * Building blocks for the cases that drive the Repsy CONTAINER itself (`tests/stack/`, README.md
 * "Stack runner"): find the container of the stack this harness owns, `docker exec` into it and read
 * its log. Only the "stack" runner has a Docker CLI and the host's Docker socket, and only a local/ci
 * target has a container this harness may exec into, so every caller skips on a remote target first.
 */
import { run } from './exec.js';

const STACK_PROJECT = process.env.REPSY_E2E_STACK_PROJECT || 'repsy-e2e';

/** The Repsy image's runtime user and the directory the marker files go into (Dockerfile). */
export const RUNTIME_USER = 'appuser';
export const MARKER_DIR = '/app/data/password-reset';

/**
 * The id of the running `repsy` service container of the compose project `REPSY_E2E_STACK_PROJECT`
 * (default `repsy-e2e`, the `name:` of both stack files), found through the labels compose puts on
 * it, so it does not depend on the container's generated name.
 */
export async function findRepsyContainer(): Promise<string> {
  const result = await run(
    'docker',
    [
      'ps',
      '--quiet',
      '--filter',
      `label=com.docker.compose.project=${STACK_PROJECT}`,
      '--filter',
      'label=com.docker.compose.service=repsy',
    ],
    { cwd: '/tmp', label: 'docker-ps' },
  );
  const ids = result.stdout.split('\n').filter((line) => line.trim() !== '');
  if (result.exitCode !== 0 || ids.length !== 1) {
    throw new Error(
      `Expected exactly one running "repsy" container of the compose project "${STACK_PROJECT}" ` +
        `(REPSY_E2E_STACK_PROJECT), found ${ids.length} (docker ps exit ${result.exitCode}). ` +
        'Start the stack with `./run.sh local up` first.',
    );
  }
  return ids[0];
}

export interface ExecResult {
  exitCode: number;
  stdout: string;
}

/** `docker exec <container> <command...>`; never rejects on a non-zero exit, read `exitCode`. */
export async function dockerExec(
  container: string,
  command: readonly string[],
): Promise<ExecResult> {
  const result = await run('docker', ['exec', container, ...command], {
    cwd: '/tmp',
    label: `docker-exec-${command[0]}`,
  });
  return { exitCode: result.exitCode, stdout: result.stdout.trim() };
}

/**
 * The container's log (stdout and stderr merged, as `docker logs` prints them) lines that contain
 * `needle`. Filtering here keeps every other line, and so every other user's reset password, out of
 * the test's own output.
 */
export async function logLinesContaining(container: string, needle: string): Promise<string[]> {
  const result = await run('docker', ['logs', container], { cwd: '/tmp', label: 'docker-logs' });
  return `${result.stdout}\n${result.stderr}`.split('\n').filter((line) => line.includes(needle));
}
