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
 * Whether a browser of the UI suite may accept the certificate of the stack (RPS-1651).
 *
 * On a TLS stack (`REPSY_E2E_TLS=1`, README.md "TLS stack") Repsy serves a certificate of a throwaway
 * CA, and `run.sh test` gives every runner that CA in `REPSY_E2E_TLS_CA_FILE`. The harness's Node clients
 * trust it through `NODE_EXTRA_CA_CERTS`; a browser keeps its own trust store, which no environment
 * variable feeds (NSS for Chromium, Firefox's own, the system one for WebKit), so a browser context ignores
 * certificate errors when the variable is set and never otherwise: a plain-HTTP or remote run keeps
 * Playwright's default (an untrusted certificate fails the navigation).
 *
 * The chain itself is proven once, by Node, in `tests/skeleton/tls-listeners.spec.ts` (`authorized` against
 * the CA, the SANs, and the control that the same request without the CA fails). `playwright.config.ts`
 * applies the same rule to the contexts Playwright makes (`use.ignoreHTTPSErrors`, on the same variable);
 * this is for the contexts a fixture opens itself with `browser.newContext()`, which do not inherit `use`.
 */
export function browserTrustOptions(
  vars: Readonly<Record<string, string | undefined>> = process.env,
): { ignoreHTTPSErrors: true } | Record<string, never> {
  return vars.REPSY_E2E_TLS_CA_FILE ? { ignoreHTTPSErrors: true } : {};
}
