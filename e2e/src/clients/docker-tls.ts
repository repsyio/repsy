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
 * The ONE place that says how `skopeo` and `regctl` reach the registry over TLS (RPS-1478 part B),
 * so the HTTPS overlay leg (RPS-1474, its per-client switch) changes this file and nothing else.
 *
 * Today the e2e stack speaks plain HTTP on the repo port (`REPSY_REPO_BASE_URL=http://...`), and
 * neither client treats `localhost` as insecure on its own the way `crane` does (ggcr's
 * `Scheme()`): skopeo tries `https://` first and only falls back to `http://` when told
 * `--tls-verify=false` (confirmed live: it logs "server gave HTTP response to HTTPS client", then
 * retries in plain HTTP), and regctl needs `"tls": "disabled"` on the registry's host entry.
 *
 *  - `http:` repo URL: plain HTTP, so verification is off (`'disabled'`).
 *  - `https:` repo URL with `REPSY_E2E_INSECURE_REGISTRY`: TLS on, certificate NOT verified
 *    (`'insecure'`; regctl's `"tls": "insecure"`, skopeo's `--tls-verify=false`).
 *  - `https:` otherwise: verified against the runner's trust store (`SSL_CERT_FILE`/`SSL_CERT_DIR`
 *    are on `clientEnv`'s allow-list), the default (`'enabled'`).
 */
import { env } from '../env.js';

export type RegistryTls = 'disabled' | 'insecure' | 'enabled';

export function registryTls(): RegistryTls {
  if (new URL(env.repoBaseUrl).protocol === 'http:') {
    return 'disabled';
  }
  return env.insecureRegistry ? 'insecure' : 'enabled';
}

/** skopeo's flag for the registry side of a command: `copy` names its two sides, the others take one. */
export function skopeoTlsFlags(side: 'src' | 'dest' | 'both'): string[] {
  if (registryTls() === 'enabled') {
    return [];
  }
  return [side === 'both' ? '--tls-verify=false' : `--${side}-tls-verify=false`];
}

/** The `tls` value of the registry's host entry in regctl's config file, or `undefined` for its default. */
export function regctlTls(): 'disabled' | 'insecure' | undefined {
  const mode = registryTls();
  return mode === 'enabled' ? undefined : mode;
}

/**
 * oras's flags for the registry of a command (RPS-1478 part C): `--plain-http` (plain HTTP, verification
 * off) or `--insecure` (TLS on, certificate not verified), none when TLS is verified. `copy` names its two
 * sides (`--from-plain-http`/`--to-plain-http`, `--from-insecure`/`--to-insecure`), every other command
 * takes one.
 */
export function orasTlsFlags(side: 'target' | 'from' | 'to' = 'target'): string[] {
  const mode = registryTls();
  if (mode === 'enabled') {
    return [];
  }
  const flag = mode === 'disabled' ? 'plain-http' : 'insecure';
  return [side === 'target' ? `--${flag}` : `--${side}-${flag}`];
}
