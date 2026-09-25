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
 * A thin `execa` wrapper every protocol adapter (`clients/<protocol>.ts`) runs its real client
 * through: an isolated working directory per invocation, an isolated `HOME` so the client's own
 * config/cache never touches the invoking user's, a timeout, and the command's captured
 * stdout/stderr attached to the running Playwright test when the command failed. It never prints or
 * attaches a secret: every value in `redact` (normally the credential's password/token) is replaced
 * with `***` in both the logged command line and the attachment before either is written anywhere.
 */
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { execa } from 'execa';
import { test as playwrightTest } from '@playwright/test';

export interface RunOptions {
  cwd: string;
  env?: NodeJS.ProcessEnv;
  /**
   * Whether `env` is merged over the runner's own `process.env` (`execa`'s default, and this
   * wrapper's) or is the child's whole environment. A client that must not see the runner's
   * variables (the admin password among them) passes `false`: the npm-family suite does, through
   * `runSealed` (RPS-1364).
   */
  extendEnv?: boolean;
  timeoutMs?: number;
  /** Argv/output fragments to replace with `***` before logging or attaching (secrets). */
  redact?: readonly string[];
  /** Used as the attachment name on failure; defaults to `command`. */
  label?: string;
  /** Piped to the process's stdin (e.g. `crane auth login --password-stdin`'s secret) -- never
   *  logged or attached, unlike argv/output, since it is never part of either. */
  input?: string;
}

export interface RunResult {
  exitCode: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
  /** The command line actually run, with every `redact` fragment replaced by `***`. */
  command: string;
}

const DEFAULT_TIMEOUT_MS = 180_000;

function redact(value: string, secrets: readonly string[]): string {
  let result = value;
  for (const secret of secrets) {
    if (secret) {
      result = result.split(secret).join('***');
    }
  }
  return result;
}

function redactedCommandLine(
  command: string,
  args: readonly string[],
  secrets: readonly string[],
): string {
  return [command, ...args].map((part) => redact(part, secrets)).join(' ');
}

/**
 * A fresh temp directory for one client invocation, with `home` and `work` subdirectories: `work`
 * is meant as the process's `cwd` (the rendered package project), `home` as its `HOME`, so the
 * client's own dotfiles/config/cache never land in the invoking user's home directory and two
 * invocations (parallel Playwright workers, or publish vs. consume in the same test) never share
 * either.
 */
export async function isolatedWorkDir(prefix: string): Promise<{ home: string; work: string }> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), `${prefix}-`));
  const home = path.join(root, 'home');
  const work = path.join(root, 'work');
  await fs.mkdir(home, { recursive: true });
  await fs.mkdir(work, { recursive: true });
  return { home, work };
}

/**
 * Runs `command` with `args`, never rejecting on a non-zero exit: the caller reads `exitCode`. On a
 * non-zero exit or a timeout, the (redacted) command line and its full stdout/stderr are attached to
 * the currently running Playwright test, if there is one (a plain script invocation outside a test
 * — none in this harness today — just skips the attach).
 */
export async function run(
  command: string,
  args: readonly string[],
  opts: RunOptions,
): Promise<RunResult> {
  const secrets = opts.redact ?? [];
  const commandLine = redactedCommandLine(command, args, secrets);

  const result = await execa(command, args, {
    cwd: opts.cwd,
    env: opts.env,
    extendEnv: opts.extendEnv ?? true,
    timeout: opts.timeoutMs ?? DEFAULT_TIMEOUT_MS,
    reject: false,
    ...(opts.input !== undefined ? { input: opts.input } : {}),
  });

  const stdout = redact(result.stdout ?? '', secrets);
  const stderr = redact(result.stderr ?? '', secrets);
  const exitCode = result.exitCode ?? -1;
  const timedOut = Boolean(result.timedOut);

  if (exitCode !== 0 || timedOut) {
    await attachOnFailure(opts.label ?? command, commandLine, exitCode, timedOut, stdout, stderr);
  }

  return { exitCode, stdout, stderr, timedOut, command: commandLine };
}

async function attachOnFailure(
  label: string,
  commandLine: string,
  exitCode: number,
  timedOut: boolean,
  stdout: string,
  stderr: string,
): Promise<void> {
  try {
    const info = playwrightTest.info();
    const body =
      `$ ${commandLine}\n` +
      `exit code: ${exitCode}${timedOut ? ' (timed out)' : ''}\n\n` +
      `--- stdout ---\n${stdout}\n\n--- stderr ---\n${stderr}\n`;
    await info.attach(`${label}-output`, { body, contentType: 'text/plain' });
  } catch {
    // Not running inside a Playwright test (no current TestInfo) — nothing to attach to.
  }
}
