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
 * Which npm-family clients the matrix specs run against (RPS-1330). Every client the runner image
 * installs is version-smoke-tested (`INSTALLED_CLIENTS`, `tests/npm-clients/versions.spec.ts`), but a
 * client only joins `ENABLED_CLIENTS` once its `NpmFamilyClient` exists and its cells are probed, in
 * the PR that adds it: that one line is what lights up every matrix cell for it. Until then the
 * matrix is the npm baseline alone.
 *
 * A matrix spec iterates `clientsWith('<capability>')` and registers a cell only for a client that has
 * the capability, never a `test.skip` (which would add a skipped row per missing cell to every
 * report); the README table lists the N/A cells.
 */
import { isolatedWorkDir, type RunResult } from '../exec.js';
import {
  CLIENT_BINARIES,
  type Capabilities,
  type ClientId,
  type NpmFamilyClient,
} from './client.js';
import { runSealed, sealedEnv } from './config.js';
import { bunClient } from './bun-client.js';
import { denoClient } from './deno-client.js';
import { npmClient } from './npm-client.js';
import { pnpmClient } from './pnpm-client.js';
import { yarnBerryClient } from './yarn-berry-client.js';
import { yarnClassicClient } from './yarn-classic-client.js';

export const ENABLED_CLIENTS: readonly NpmFamilyClient[] = [
  npmClient,
  pnpmClient,
  yarnClassicClient,
  bunClient,
  yarnBerryClient,
  // Consume-only, every capability off: its own cells are tests/npm-clients/deno/ (deno-client.ts).
  denoClient,
];

/** The enabled clients that can do what a cell needs. */
export function clientsWith(capability: keyof Capabilities): NpmFamilyClient[] {
  return ENABLED_CLIENTS.filter((client) => client.caps[capability]);
}

export interface InstalledClient {
  id: ClientId;
  label: string;
  tag: `@${string}`;
}

/** Every client the `npm-clients` runner image installs, registered or not. */
export const INSTALLED_CLIENTS: readonly InstalledClient[] = [
  { id: 'npm', label: 'npm', tag: '@npm' },
  { id: 'pnpm', label: 'pnpm', tag: '@pnpm' },
  { id: 'yarn-classic', label: 'yarn-classic', tag: '@yarn-classic' },
  { id: 'yarn-berry', label: 'yarn-berry', tag: '@yarn-berry' },
  { id: 'bun', label: 'bun', tag: '@bun' },
  { id: 'deno', label: 'deno', tag: '@deno' },
];

/** `<binary> --version` of an installed client, by its absolute path, in the sealed environment. */
export async function versionOf(id: ClientId): Promise<RunResult> {
  const { home, work } = await isolatedWorkDir(`npmc-${id}-version`);
  return runSealed(CLIENT_BINARIES[id], ['--version'], {
    cwd: work,
    env: sealedEnv(home),
    timeoutMs: 60_000,
    label: `${id}-version`,
  });
}
