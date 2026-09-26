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
 * The environment of a real package-manager client (`mvn`, `gradle`, `sbt`, `ant`, `cargo`,
 * `dotnet`, `python3`, `go`, `gem`, `bundle`, `helm`, `crane`, `npm`, `curl`) as an allow-list
 * (RPS-1446), the counterpart of the npm-family's `sealedEnv` (`npm-family/config.ts`, RPS-1364)
 * for every other suite. A client used to get `{ ...process.env, HOME }`, so `REPSY_ADMIN_PASSWORD`
 * and every other runner variable were visible to it, to its plugins and to the scripts it runs,
 * and would land in any log a client dumps its environment into. Now it gets exactly:
 *
 * - `HOME`, the invocation's isolated directory;
 * - the `BASE_VARIABLES` below, copied from the runner when set (executables, locale, temp
 *   directory, proxy and TLS trust: what any process needs to find its tools and reach the
 *   network);
 * - the `inherit` list of the client, the runner variables its toolchain really needs (found by
 *   running the suite without it, see each call site), copied when set;
 * - `extra`, the client's own settings (its config/cache directories, its credentials).
 *
 * `run()` (`exec.ts`) hands an explicit `env` to the child as its WHOLE environment, and refuses a
 * `REPSY_*` key in it, so a call site that forgets the allow-list cannot leak either.
 */

/**
 * Copied from the runner when set. `PATH` is how every client finds its own executables and the
 * tools it shells out to (the runner images put the toolchain there: `/opt/java/.../bin`,
 * `/usr/local/cargo/bin`, `/usr/local/go/bin`, `/usr/share/dotnet`). The rest: locale and time zone
 * (tool output and date handling), `TMPDIR` (the run's temp directory), and the proxy / TLS-trust
 * variables a runner behind a corporate proxy or a private CA would need (none is set in the
 * runner images, so they are absent unless an operator adds them; without them a client could not
 * reach a remote target from such a network).
 */
export const BASE_VARIABLES: readonly string[] = [
  'PATH',
  'LANG',
  'LC_ALL',
  'TZ',
  'TMPDIR',
  'HTTP_PROXY',
  'HTTPS_PROXY',
  'NO_PROXY',
  'http_proxy',
  'https_proxy',
  'no_proxy',
  'SSL_CERT_FILE',
  'SSL_CERT_DIR',
  'CURL_CA_BUNDLE',
];

/** A client's environment: see this file's header. `extra` wins over the copied variables. */
export function clientEnv(
  home: string,
  extra: NodeJS.ProcessEnv = {},
  inherit: readonly string[] = [],
): NodeJS.ProcessEnv {
  const result: NodeJS.ProcessEnv = { HOME: home };
  for (const name of [...BASE_VARIABLES, ...inherit]) {
    const value = process.env[name];
    if (value !== undefined) {
      result[name] = value;
    }
  }
  result.PATH ??= '/usr/local/bin:/usr/bin:/bin';
  return { ...result, ...extra };
}
