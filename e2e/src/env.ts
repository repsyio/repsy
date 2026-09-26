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

import 'dotenv/config';

/**
 * Where the harness is pointed:
 *  - local:  a stack this harness started and owns (`./run.sh local up`); throttle limits and
 *            other server settings can be tuned for it.
 *  - ci:     a pipeline-started stack, same freedoms as `local`.
 *  - remote: an already-running instance the harness does not own or reset; nothing global on it
 *            (the `admin` user, the default repos) is ever touched.
 */
export type RepsyTarget = 'local' | 'remote' | 'ci';

const TARGETS: readonly RepsyTarget[] = ['local', 'remote', 'ci'];

export interface Env {
  apiBaseUrl: string;
  repoBaseUrl: string;
  /**
   * The plain-http URLs of the same stack when it also serves TLS (the `tls` overlay, README.md "TLS
   * stack"), where `apiBaseUrl`/`repoBaseUrl` are https. Equal to them otherwise.
   */
  plainApiBaseUrl: string;
  plainRepoBaseUrl: string;
  adminUsername: string;
  adminPassword: string;
  target: RepsyTarget;
  runId: string;
  insecureRegistry: boolean;
}

const RUN_ID_ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';

/**
 * A short random id, lowercase alphanumeric only so it satisfies both the repo-name pattern
 * (`^[a-zA-Z0-9_][a-zA-Z0-9_\-]*$`) and the stricter username pattern (`^[a-z0-9_\-]+$`), and short
 * enough that `e2e-<runid>-<proto>-<n>` (the longest protocol name, "golang", is 6 chars) stays
 * within the repo name's 25-char limit.
 */
function randomRunId(length = 6): string {
  let id = '';
  for (let i = 0; i < length; i += 1) {
    id += RUN_ID_ALPHABET[Math.floor(Math.random() * RUN_ID_ALPHABET.length)];
  }
  return id;
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} is required and has no default. Set it in e2e/.env (copy .env.example) or export it.`,
    );
  }
  return value;
}

function parseTarget(value: string | undefined): RepsyTarget {
  const target = value || 'local';
  if (!TARGETS.includes(target as RepsyTarget)) {
    throw new Error(`REPSY_TARGET must be one of ${TARGETS.join(', ')}, got "${target}"`);
  }
  return target as RepsyTarget;
}

function loadEnv(): Env {
  const apiBaseUrl = process.env.REPSY_API_BASE_URL || 'http://localhost:8080';
  const repoBaseUrl = process.env.REPSY_REPO_BASE_URL || 'http://localhost:9090';
  return {
    apiBaseUrl,
    repoBaseUrl,
    plainApiBaseUrl: process.env.REPSY_E2E_PLAIN_API_BASE_URL || apiBaseUrl,
    plainRepoBaseUrl: process.env.REPSY_E2E_PLAIN_REPO_BASE_URL || repoBaseUrl,
    adminUsername: process.env.REPSY_ADMIN_USERNAME || 'admin',
    adminPassword: required('REPSY_ADMIN_PASSWORD'),
    target: parseTarget(process.env.REPSY_TARGET),
    runId: process.env.REPSY_E2E_RUN_ID || randomRunId(),
    insecureRegistry: Boolean(process.env.REPSY_E2E_INSECURE_REGISTRY),
  };
}

export const env: Env = loadEnv();
