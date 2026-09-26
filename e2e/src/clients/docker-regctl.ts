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
 * `regctl` (regclient, daemonless) as the third Docker client (RPS-1478 part B): the scenario-loop
 * adapter (`regctlAdapter`, the whole catalog through `regctl image copy`, see
 * `docker-copy-adapter.ts`) and the config renderer the client-specific tests use
 * (`tests/docker/regctl.spec.ts`).
 *
 * How this client differs from `crane`/`skopeo` on the wire (probed live against a Repsy Docker repo):
 *  - It pings `/v2/` itself (no TLS attempt: `"tls": "disabled"` in the host entry, `docker-tls.ts`)
 *    and follows the Bearer challenge; its own token scope is `repository:<repo>/<image>:pull,push` and it
 *    never asks for `delete` up front, but it merges the scope of the challenge into its token request:
 *    the unauthenticated DELETE of `regctl manifest delete`/`tag delete` is challenged with
 *    `scope="repository:<repo>/<image>:delete"` (RPS-1588), so the one token it fetches already carries
 *    `delete,pull,push` and the delete is accepted with no `insufficient_scope` round trip (before
 *    RPS-1588 the challenge named a constant scope and regctl took that round trip, the "older crane"
 *    one of `tests/docker/crane-delete.spec.ts`).
 *  - `regctl manifest delete` needs a DIGEST reference (it refuses a tag); `regctl tag delete` is the
 *    tag one.
 *  - A local OCI layout is addressed by digest (`ocidir://<dir>@sha256:...`) because the hand-built
 *    layout's `index.json` carries no `ref.name` annotation for a tag.
 *
 * Credentials: a rendered `regctl.json` (`{"hosts": {"<host>": {"tls", "user", "pass"}}}`, what
 * `regctl registry set/login` writes) pointed at by `REGCTL_CONFIG`: never `-p`/`--pass`, so no
 * secret is in argv. The anonymous credential renders a host entry without `user`/`pass`.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import type { MaterializedCredential } from '../scenarios/world.js';
import { clientEnv } from './client-env.js';
import { copyAdapter, type CopyClient } from './docker-copy-adapter.js';
import { regctlTls } from './docker-tls.js';
import { readResolvedImage } from './docker.js';
import { registryHost } from './docker-raw.js';

export function regctlConfigPath(home: string): string {
  return path.join(home, 'regctl.json');
}

export function regctlEnv(home: string): NodeJS.ProcessEnv {
  return clientEnv(home, { REGCTL_CONFIG: regctlConfigPath(home) });
}

/** `regctl.json` in `home`, rendered fresh per invocation (never a machine-wide config). */
export async function renderRegctlConfig(
  home: string,
  credential: MaterializedCredential,
): Promise<string> {
  const entry: Record<string, unknown> = { hostname: registryHost() };
  const tls = regctlTls();
  if (tls !== undefined) {
    entry.tls = tls;
  }
  if (credential.transport === 'basic') {
    entry.user = credential.username ?? '';
    entry.pass = credential.password ?? '';
  }
  const file = regctlConfigPath(home);
  await fs.writeFile(file, JSON.stringify({ hosts: { [registryHost()]: entry } }), {
    encoding: 'utf8',
    mode: 0o600,
  });
  return file;
}

export const regctlClient: CopyClient = {
  name: 'regctl',
  publishVerb: 'image copy (to the registry)',
  consumeVerb: 'image copy (from the registry)',
  env: regctlEnv,
  renderAuth: async (home, credential) => {
    await renderRegctlConfig(home, credential);
  },
  pushArgs: (built, ref) => ['image', 'copy', `ocidir://${built.dir}@${built.manifestDigest}`, ref],
  pullArgs: (ref, destDir) => ['image', 'copy', ref, `ocidir://${destDir}`],
  readPulled: readResolvedImage,
};

export const regctlAdapter = copyAdapter(regctlClient);
