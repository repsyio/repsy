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
 * `oras` (oras-project, daemonless) as the fourth Docker client (RPS-1478 part C): the OCI ARTIFACT
 * client (`push`/`pull` of arbitrary files with an `artifactType`, `attach` of a referrer, `discover`,
 * `copy`, `manifest`/`blob`), so the `tests/docker/oras.spec.ts` cells meet the parts of the OCI
 * distribution spec the image clients never touch. It has no scenario-loop adapter: what it does that
 * `crane`/`skopeo`/`regctl` do not is what the spec pins.
 *
 * Credentials: `oras login --password-stdin` into an isolated auth file (`--registry-config`, docker's
 * `config.json` shape) inside the invocation's private `HOME`: oras's own store, the secret on stdin and
 * never in argv. The anonymous credential logs nothing in (the file is then absent, which oras accepts).
 * The client runs in a `clientEnv` allow-list environment; every command names its own
 * `--registry-config`, so nothing of the runner's `HOME` or of another invocation is read.
 *
 * TLS is `docker-tls.ts`'s: `--plain-http` today (oras does not treat `localhost` as insecure).
 */
import path from 'node:path';

import type { MaterializedCredential } from '../scenarios/world.js';
import { clientEnv } from './client-env.js';
import { secretsOf } from './docker-copy-adapter.js';
import { registryHost } from './docker-raw.js';
import { orasTlsFlags } from './docker-tls.js';
import { isolatedWorkDir, run, type RunResult } from './exec.js';

export function orasEnv(home: string): NodeJS.ProcessEnv {
  return clientEnv(home);
}

export function orasAuthPath(home: string): string {
  return path.join(home, 'oras-auth.json');
}

/** The distribution-spec switch that makes oras use the referrers TAG schema instead of the API. */
export const REFERRERS_TAG = 'v1.1-referrers-tag';

export interface OrasSession {
  readonly home: string;
  readonly work: string;
  /** `--registry-config <auth file>` plus the TLS flag of the target: what nearly every command takes. */
  readonly common: string[];
  /** The same for `oras copy`, which names its source (`--from-...`) and destination (`--to-...`). */
  readonly copyCommon: string[];
  /** Runs `oras` with `args`: the whole command line after the binary name. */
  run(args: string[], label: string): Promise<RunResult>;
}

/** A private `HOME` with the credential logged in through oras's own store, and `oras` runs against it. */
export async function openOrasSession(
  credential: MaterializedCredential,
  label: string,
): Promise<OrasSession> {
  const { home, work } = await isolatedWorkDir(label);
  const auth = orasAuthPath(home);
  const redact = secretsOf(credential);
  const exec = (args: string[], stepLabel: string, input?: string) =>
    run('oras', args, {
      cwd: work,
      env: orasEnv(home),
      timeoutMs: 120_000,
      redact,
      label: stepLabel,
      ...(input !== undefined ? { input } : {}),
    });

  if (credential.transport === 'basic') {
    const login = await exec(
      [
        'login',
        '--registry-config',
        auth,
        ...orasTlsFlags(),
        '--username',
        credential.username ?? '',
        '--password-stdin',
        registryHost(),
      ],
      `${label}-login`,
      credential.password ?? '',
    );
    if (login.exitCode !== 0) {
      throw new Error(`oras login failed (${login.exitCode}): ${login.stderr}`);
    }
  }
  return {
    home,
    work,
    common: ['--registry-config', auth, ...orasTlsFlags()],
    copyCommon: [
      '--from-registry-config',
      auth,
      '--to-registry-config',
      auth,
      ...orasTlsFlags('from'),
      ...orasTlsFlags('to'),
    ],
    run: (args, stepLabel) => exec(args, stepLabel),
  };
}
