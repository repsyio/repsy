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

// ---------------------------------------------------------------------------------------------------
// Restart and recreate (RPS-1476). tests/stack/persistence.spec.ts uses these to prove what survives a
// restart of the Repsy container and a recreate of it on the same volumes, and tests/stack/upgrade
// (RPS-1487) recreates it on another image. They only ever touch the one "repsy" container of the
// project `REPSY_E2E_STACK_PROJECT` (never postgres, never another stack), and only on a stack this
// harness owns: every caller skips on a remote target first.
//
// Whatever the container does on the way, the harness's own session is gone: a restart with
// `OS_APP_JWT_SECRET` unset regenerates the secret, so the admin JWT a `PanelApi` holds answers 401
// afterwards. Call `PanelApi.login()` again before the next panel call (a Basic-auth protocol client is
// not affected, it authenticates per request).
// ---------------------------------------------------------------------------------------------------

const HEALTH_TIMEOUT_MS = 180_000;
const HEALTH_INTERVAL_MS = 1_000;

/** The compose files a stack was started from and the directory they live in (as the HOST sees it). */
export interface ComposeFiles {
  /** Absolute paths, in the order compose merges them (`-f` order). */
  files: string[];
  workingDir: string;
}

async function docker(args: readonly string[], label: string, timeoutMs = 60_000) {
  const result = await run('docker', args, { cwd: '/tmp', label, timeoutMs });
  if (result.exitCode !== 0) {
    throw new Error(`${result.command} failed (exit ${result.exitCode}): ${result.stderr.trim()}`);
  }
  return result.stdout.trim();
}

/**
 * The compose files the running `repsy` container was created from, read off the labels compose puts on
 * it (`com.docker.compose.project.config_files` and `.working_dir`). They are absolute host paths, which
 * the stack runner can read because docker-compose.runners.yml mounts `REPSY_E2E_HOST_DIR` (the e2e
 * directory) read-only at the same path. Take this BEFORE an overlay recreate: after it the labels
 * name the overlay too.
 */
export async function currentComposeFiles(container?: string): Promise<ComposeFiles> {
  const id = container ?? (await findRepsyContainer());
  const out = await docker(
    [
      'inspect',
      '--format',
      '{{index .Config.Labels "com.docker.compose.project.config_files"}}|' +
        '{{index .Config.Labels "com.docker.compose.project.working_dir"}}',
      id,
    ],
    'docker-inspect-labels',
  );
  const [configFiles, workingDir] = out.split('|');
  const files = (configFiles ?? '').split(',').filter((file) => file !== '');
  if (files.length === 0 || !workingDir) {
    throw new Error(
      `The container ${id} carries no compose config_files/working_dir labels: ${out}`,
    );
  }
  return { files, workingDir };
}

/** `docker inspect` of the container's start time (changes on every restart/recreate) and health. */
async function stateOf(container: string): Promise<{ startedAt: string; health: string }> {
  const out = await docker(
    [
      'inspect',
      '--format',
      '{{.State.StartedAt}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}',
      container,
    ],
    'docker-inspect-state',
  );
  const [startedAt, health] = out.split('|');
  return { startedAt: startedAt ?? '', health: health ?? '' };
}

/**
 * Waits until `container` has started again after `previousStartedAt` and its healthcheck (the SPA's
 * static root, docker-compose.stack.yml) reports healthy. `docker restart` returns as soon as the
 * process is started, and the health status of the previous run can still be readable for an instant, so
 * the start time is compared too.
 */
export async function waitHealthy(
  container: string,
  previousStartedAt?: string,
  timeoutMs = HEALTH_TIMEOUT_MS,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const state = await stateOf(container);
    last = `${state.health} (started ${state.startedAt})`;
    if (state.health === 'healthy' && state.startedAt !== previousStartedAt) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, HEALTH_INTERVAL_MS));
  }
  throw new Error(`Repsy container ${container} is not healthy after ${timeoutMs} ms: ${last}`);
}

/**
 * `docker restart` of the Repsy container (SIGTERM, then SIGKILL after `stopTimeoutSeconds`, default
 * Docker's 10 s), then waits for it to be healthy again. The container, its anonymous volumes and its
 * environment are unchanged. Returns the container id.
 */
export async function restartRepsy(stopTimeoutSeconds = 10): Promise<string> {
  const container = await findRepsyContainer();
  const before = await stateOf(container);
  await docker(
    ['restart', '--time', String(stopTimeoutSeconds), container],
    'docker-restart',
    stopTimeoutSeconds * 1_000 + 60_000,
  );
  await waitHealthy(container, before.startedAt);
  return container;
}

/**
 * `docker kill --signal KILL` then `docker start`: the process gets no chance to shut down (no shutdown
 * hook, no flush of the H2 file database), as after an OOM kill or a power cut. Same container, same
 * volumes. Waits for the container to be healthy again and returns its id.
 */
export async function crashRepsy(): Promise<string> {
  const container = await findRepsyContainer();
  const before = await stateOf(container);
  await docker(['kill', '--signal', 'KILL', container], 'docker-kill');
  await docker(['start', container], 'docker-start');
  await waitHealthy(container, before.startedAt);
  return container;
}

export interface RecreateOptions {
  /**
   * Variables the stack files interpolate, set for this recreate only: `{ REPSY_E2E_JWT_SECRET: '...' }`
   * for docker-compose.stack-jwt.yml, `{ REPSY_IMAGE: 'repo.repsy.io/repsy/os/repsy:26.08.4' }` for another
   * image (RPS-1487). Anything not given is what the runner's own environment says, exactly what
   * `run.sh local up` used: the ports, the image tag, `REPSY_ADMIN_PASSWORD`.
   */
  env?: Record<string, string>;
  /** Overlay files (paths relative to the stack's working directory, or absolute) merged after `files`. */
  extraFiles?: readonly string[];
  /**
   * The files to start from. Default: the ones the running container was created from
   * (`currentComposeFiles`). Pass the files taken before an overlay recreate to go back to the plain stack.
   */
  files?: ComposeFiles;
}

/**
 * Recreates the Repsy container: `docker compose -p <project> -f ... up -d --no-deps --no-build
 * --force-recreate --wait repsy`, from the compose files the container was created from (plus the
 * overlays in `extraFiles`). The old container is removed and a new one created, with the same
 * project, image tag and ports, and the anonymous volumes of the old one carried over (`/app/data`,
 * where the H2 file database and the storage live): what `docker compose up` after an image pull does
 * in a real deployment. It does not build, and it leaves postgres and the volumes alone. Returns the new
 * container id.
 *
 * The overlay stays in effect for the new container's labels: to undo it, recreate again with the
 * `ComposeFiles` you took before (see `restoreStack`).
 */
export async function recreateRepsy(options: RecreateOptions = {}): Promise<string> {
  const previous = await findRepsyContainer();
  const base = options.files ?? (await currentComposeFiles(previous));
  const extra = (options.extraFiles ?? []).map((file) =>
    file.startsWith('/') ? file : `${base.workingDir}/${file}`,
  );
  const fileArgs = [...base.files, ...extra].flatMap((file) => ['-f', file]);
  const result = await run(
    'docker',
    [
      'compose',
      '-p',
      STACK_PROJECT,
      '--project-directory',
      base.workingDir,
      ...fileArgs,
      'up',
      '--detach',
      '--no-deps',
      '--no-build',
      '--force-recreate',
      '--wait',
      '--wait-timeout',
      String(HEALTH_TIMEOUT_MS / 1_000),
      'repsy',
    ],
    {
      cwd: '/tmp',
      env: options.env ?? {},
      // The runner's own environment carries what the stack files interpolate (ports, image tag,
      // REPSY_ADMIN_PASSWORD); `env` only adds to it.
      extendEnv: true,
      timeoutMs: HEALTH_TIMEOUT_MS + 60_000,
      label: 'docker-compose-recreate',
    },
  );
  if (result.exitCode !== 0) {
    throw new Error(`${result.command} failed (exit ${result.exitCode}): ${result.stderr.trim()}`);
  }
  const container = await findRepsyContainer();
  if (container === previous) {
    throw new Error(`docker compose kept the container ${previous}: it was not recreated`);
  }
  return container;
}

/** Recreates the Repsy container from `original` (`currentComposeFiles`), dropping any overlay and env. */
export async function restoreStack(original: ComposeFiles): Promise<string> {
  return recreateRepsy({ files: original });
}

/**
 * How `/app/data` is mounted in the Repsy container: `volume:<name>` (Docker's anonymous volume, the
 * image's `VOLUME /app/data`, or a named one) or `bind:<host path>`; `undefined` when nothing is mounted
 * there, which means the data lives in the container's own layer and dies with it (RPS-1401). A recreate
 * that keeps the data gives the same string before and after.
 */
export async function dataMount(container?: string): Promise<string | undefined> {
  const id = container ?? (await findRepsyContainer());
  const out = await docker(
    [
      'inspect',
      '--format',
      '{{range .Mounts}}{{if eq .Destination "/app/data"}}{{.Type}}:{{if .Name}}{{.Name}}{{else}}{{.Source}}{{end}}{{end}}{{end}}',
      id,
    ],
    'docker-inspect-mounts',
  );
  return out === '' ? undefined : out;
}

// ---------------------------------------------------------------------------------------------------
// The upgrade path (RPS-1487, tests/stack/upgrade.spec.ts): which image a container runs, and waiting for
// a line the application logs some time after it is healthy (a background job).
// ---------------------------------------------------------------------------------------------------

/** The image reference the container was created from (`docker inspect .Config.Image`). */
export async function containerImage(container?: string): Promise<string> {
  const id = container ?? (await findRepsyContainer());
  return docker(['inspect', '--format', '{{.Config.Image}}', id], 'docker-inspect-image');
}

/**
 * The container's log lines matching `pattern`, polled until at least one does or `timeoutMs` passes
 * (then the last, possibly empty, result). Only the matching lines are returned, so a password another
 * line carries stays out of the test's output.
 */
export async function waitForLogLines(
  container: string,
  pattern: RegExp,
  timeoutMs = 60_000,
): Promise<string[]> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const result = await run('docker', ['logs', container], { cwd: '/tmp', label: 'docker-logs' });
    const lines = `${result.stdout}\n${result.stderr}`
      .split('\n')
      .filter((line) => pattern.test(line));
    if (lines.length > 0 || Date.now() >= deadline) {
      return lines;
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
}
