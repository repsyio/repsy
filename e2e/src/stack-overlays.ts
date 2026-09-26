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

/**
 * Opt-in suites: specs that only make sense on a stack started with an overlay (README.md "Stack
 * overlays": `./run.sh local up --throttle`, `--scanner`, ...), or that are switched on by hand. They
 * skip themselves instead of being filtered out by a `grepInvert` in the config:
 *
 *   test.skip(!optedIn('throttle'), 'needs the throttle overlay: ./run.sh local up --throttle');
 *
 * The set is the comma list `REPSY_E2E_OPT_IN`. `run.sh test` adds the name of every overlay whose
 * switch (`REPSY_E2E_THROTTLE=1`, `REPSY_E2E_SCANNER=1`, ...) is set, and `docker-compose.runners.yml`
 * forwards it to every runner. `REPSY_UI_OPT_IN`, the name the UI suite used before the overlays were
 * shared, still counts, so `REPSY_UI_OPT_IN=throttle ./run.sh test --protocol ui` keeps working.
 * Names compare case-insensitively.
 *
 * An opt-in spec that skips in the nightly leg made for it proves nothing, so the nightly asserts that
 * the runners of such a leg report no skipped tests (`require_no_skips`, e2e-nightly.yml).
 */

/** The opted-in suite names of this process, lower-cased. */
export function optInNames(): string[] {
  return [process.env.REPSY_E2E_OPT_IN, process.env.REPSY_UI_OPT_IN]
    .flatMap((list) => (list ?? '').split(','))
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => entry.length > 0);
}

/** Whether the opt-in suite `name` was requested; see the file header. */
export function optedIn(name: string): boolean {
  return optInNames().includes(name.toLowerCase());
}
