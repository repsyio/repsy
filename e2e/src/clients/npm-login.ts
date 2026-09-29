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
 * Drives a REAL, interactive `npm login` (RPS-1717): unlike every other npm operation this harness
 * runs (credentials always arrive via a rendered `.npmrc`, `npm.ts`'s `renderNpmrc`), `npm login`'s
 * own prompts (`lib/utils/read-user-info.js`, via the `read` package) are TTY-gated -- they behave
 * differently, or refuse outright, without a controlling terminal. Node has no built-in PTY support
 * and this repository vendors no PTY library (no `node-pty` in `e2e/package.json`), so this uses the
 * `script(1)` fallback: confirmed live to be present with no Dockerfile change in the
 * `node:24-bookworm-slim` runner image (`bsdutils`'s `/usr/bin/script`, util-linux 2.39.3).
 *
 * `script` allocates a real pseudo-terminal for its child regardless of its own stdin. `-q`
 * suppresses script's own "Script started/done" banner (only actually suppressed when the log target
 * is a real file -- `/dev/null` here, so the typescript copy is discarded and only the real
 * stdout/stderr this file captures through the pipe is used). `-e` makes script exit with the CHILD's
 * real exit code instead of always 0. `script -c` runs the whole command through `sh -c` as one
 * string, so every argv element is quoted with `shQuote`.
 *
 * **Why the answers are typed prompt-by-prompt, not piped upfront (RPS-1717, found live).** The
 * first version of this file wrote `username\npassword\n` to stdin all at once (the same shape
 * execa's `input` option, or `crane auth login --password-stdin`, use for a non-interactive prompt)
 * and closed it. Against a real registry that worked for the FIRST prompt (Username) but then hung
 * forever on the second (Password) until execa's timeout killed it (confirmed with `--loglevel
 * silly`, npm's own debug log, and a hung, not crashed, process). Root cause, read from the `read`
 * package's source (`node_modules/read/dist/commonjs/read.js`): `npm login` calls `read()` freshly for
 * EACH prompt, and every call builds its own `readline.createInterface` over the SAME `process.stdin`
 * and closes it (`rl.close()`, which un-sets raw mode on a TTY) once its one line resolves. When both
 * answers are already sitting in the pty's kernel buffer before the second `readline` interface is
 * even created, the bytes intended for the password prompt are consumed by -- and lost with -- the
 * first interface's internal buffering during that close/recreate window; nothing is left for the
 * second interface to read, so it hangs. Watching stdout for each literal prompt string
 * (`usernamePrompt`/`passwordPrompt` in `read-user-info.js`) and writing the matching answer only
 * once that prompt has actually been rendered avoids the race entirely -- confirmed live: with this
 * ordering the whole login (couch PUT included) completes in well under a second.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { execa } from 'execa';

import { repoUrl } from '../repo-url.js';
import { isolatedWorkDir } from './exec.js';
import { isolatedCache, npmEnv } from './npm.js';

const LOGIN_TIMEOUT_MS = 30_000;
/** How long to keep stdin open after the last answer is sent, before ending it: `script` tears its
 *  whole session down once its own stdin sees EOF (confirmed live: ending it immediately, before npm
 *  had drained the answer, produced the exact same hang/kill as piping the answers upfront did), so
 *  this waits for npm to actually finish reading them first. */
const STDIN_DRAIN_MS = 200;

/** Strips ANSI escape sequences (SGR colours, cursor movement, OSC) from a terminal-driven client's
 *  raw output, so a prompt-detection regex (or an assertion) matches the literal text underneath. */
function stripAnsi(value: string): string {
  // eslint-disable-next-line no-control-regex -- ANSI escapes are, by definition, control characters.
  return value.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '').replace(/\x1b\][^\x07]*(\x07|\x1b\\)/g, '');
}

/** Quotes one argv element for a POSIX `sh -c` string (see this file's header). */
function shQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

function redact(value: string, secrets: readonly string[]): string {
  let result = value;
  for (const secret of secrets) {
    if (secret) {
      result = result.split(secret).join('***');
    }
  }
  return result;
}

export interface InteractiveLoginResult {
  exitCode: number;
  timedOut: boolean;
  command: string;
  /** Combined, redacted stdout+stderr of the real, PTY-driven `npm login` process. */
  output: string;
  npmrcPath: string;
  /** The `--userconfig` file's contents right after the attempt: unchanged from what was pre-seeded
   *  when login failed (npm never calls `config.save('user')` on a thrown error), a fresh
   *  `//host/path/:_authToken=...` line appended when it succeeded. */
  npmrcContents: string;
  /** The isolated `HOME`/`work` directories of this invocation, and the `--cache` directory login
   *  itself used -- reused by a caller that wants to prove the written credential works with a real
   *  follow-up `npm publish`/`npm install`, without re-isolating or re-authenticating. */
  home: string;
  work: string;
  cacheDir: string;
}

/**
 * Runs a real, interactive `npm login` against `opts.repoName`'s registry, typing `opts.username` /
 * `opts.password` to the prompts as they actually appear (see this file's header for why upfront,
 * pre-buffered input is unsafe here). `opts.scope` adds `--scope <scope>`, which npm maps to the
 * registry in the saved config (`<scope>:registry=`) in addition to the credential line.
 *
 * `opts.persistRegistry` (default true) pre-seeds the `--userconfig` file with `registry=<url>`
 * before running login: `npm login` only persists `registry=` into the config file itself when
 * `--scope` is given (it maps the scope, not the bare registry -- `lib/commands/login.js`), so an
 * UNSCOPED follow-up client call would otherwise need its own `--registry` to find the repository
 * again. Pass `false` for a scoped-login test that must prove the `<scope>:registry=` mapping ALONE
 * resolves the registry, with no bare `registry=` line to fall back on.
 */
export async function npmLoginInteractive(opts: {
  repoName: string;
  username: string;
  password: string;
  scope?: string;
  persistRegistry?: boolean;
  label: string;
}): Promise<InteractiveLoginResult> {
  const { home, work } = await isolatedWorkDir(opts.label);
  const npmrcPath = path.join(home, 'npmrc');
  const registryUrl = repoUrl(opts.repoName, '');

  const seeded = opts.persistRegistry !== false ? `registry=${registryUrl}\n` : '';
  await fs.writeFile(npmrcPath, seeded, 'utf8');
  const cacheDir = await isolatedCache(home);

  const loginArgv = [
    'npm',
    'login',
    '--registry',
    registryUrl,
    '--auth-type',
    'legacy',
    ...(opts.scope ? ['--scope', opts.scope] : []),
    '--userconfig',
    npmrcPath,
    '--cache',
    cacheDir,
  ];
  const shellCommand = loginArgv.map(shQuote).join(' ');
  const secrets = opts.password ? [opts.password] : [];
  const commandLine = redact(`script -qec ${shQuote(shellCommand)} /dev/null`, secrets);

  const subprocess = execa('script', ['-qec', shellCommand, '/dev/null'], {
    cwd: work,
    env: npmEnv(home),
    extendEnv: false,
    timeout: LOGIN_TIMEOUT_MS,
    reject: false,
  });

  let seen = '';
  let sentUsername = false;
  let sentPassword = false;
  subprocess.stdout?.on('data', (chunk: Buffer) => {
    seen += stripAnsi(chunk.toString('utf8'));
    if (!sentUsername && /Username:/.test(seen)) {
      sentUsername = true;
      subprocess.stdin?.write(`${opts.username}\n`);
    }
    if (!sentPassword && /Password:/.test(seen)) {
      sentPassword = true;
      subprocess.stdin?.write(`${opts.password}\n`);
      // `script` tears its whole pty session down the moment ITS OWN stdin sees EOF -- give npm
      // time to actually drain and act on the password line first (see this file's header).
      setTimeout(() => {
        try {
          subprocess.stdin?.end();
        } catch {
          // The process may already have exited (a fast refusal) -- nothing to end.
        }
      }, STDIN_DRAIN_MS);
    }
  });

  const result = await subprocess;
  const npmrcContents = await fs.readFile(npmrcPath, 'utf8').catch(() => '');

  return {
    exitCode: result.exitCode ?? -1,
    timedOut: Boolean(result.timedOut),
    command: commandLine,
    output: redact(`${result.stdout ?? ''}\n${result.stderr ?? ''}`, secrets),
    npmrcPath,
    npmrcContents,
    home,
    work,
    cacheDir,
  };
}
