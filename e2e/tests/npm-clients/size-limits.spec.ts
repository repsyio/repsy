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

/**
 * RPS-1482 / RPS-1561: the same size-limit leg as `tests/npm/size-limits.spec.ts`, run through each
 * npm-family publisher (RPS-1330) instead of the plain `npm` client: pnpm, yarn classic, yarn berry
 * and bun (`clientsWith('publish')` minus `npm` itself, which already has its own file and its own
 * adapter, `clients/npm.ts` -- re-running it here through the npm-family wrapper would only prove the
 * wrapper again, not the server). One `registerSizeLimitSpecs` call per client.
 *
 * Every client's refusal message below is what it printed live against the `--limits` overlay
 * (`docker-compose.stack-limits.yml`, `NPM_MAX_PUBLISH_SIZE: 64KB`), and every one of them answered
 * Repsy's standard `payloadTooLarge` envelope on the raw replay, so `registerSizeLimitSpecs`'s default
 * `expectReplay` (`expectPayloadTooLarge`) applies to all four, no override needed:
 *
 *  - pnpm:         `Error: ERR_PNPM_FAILED_TO_PUBLISH` / `413 Payload Too Large`, exit 1
 *  - yarn classic: `error Couldn't publish package: "...: Request \"...\" returned a 413"`, exit 1 --
 *    NOT its own `exitQuirk` (`yarn-classic-client.ts`): that quirk is 400/401/404 only ("resolves
 *    them as a soft rejection"), and a 413 was probed to fail normally, exit 1, not the quirky exit 0
 *  - yarn berry:   `YN0035: ... Response Code: 413 (Payload Too Large)`, exit 1
 *  - bun:          `413: http://<host>/<repo>/<package>`, exit 1
 */
import { RepoType } from '../../src/api/panel-api.js';
import { npmFamilyAdapter } from '../../src/clients/npm-family/adapter.js';
import type { NpmFamilyClient } from '../../src/clients/npm-family/client.js';
import { clientsWith } from '../../src/clients/npm-family/registry.js';
import { pushNpmFamily } from '../../src/clients/oversize.js';
import { registerSizeLimitSpecs } from '../../src/scenarios/size-limits.js';

/** Each client's own stable part of what it prints for a 413, probed live (see the file header). */
const CLIENT_MESSAGE: Record<string, RegExp> = {
  pnpm: /ERR_PNPM_FAILED_TO_PUBLISH/,
  'yarn-classic': /returned a 413/,
  'yarn-berry': /Response Code: 413/,
  bun: /413: http/,
};

for (const client of clientsWith('publish').filter(
  (candidate: NpmFamilyClient) => candidate.id !== 'npm',
)) {
  const clientMessage = CLIENT_MESSAGE[client.id];
  if (clientMessage === undefined) {
    throw new Error(
      `tests/npm-clients/size-limits.spec.ts: no probed refusal message for "${client.id}"; add ` +
        'one to CLIENT_MESSAGE (probe live, do not guess) before it can join this suite.',
    );
  }

  registerSizeLimitSpecs({
    protocol: 'npm',
    client: client.label,
    repoType: RepoType.NPM,
    adapter: npmFamilyAdapter(client),
    push: pushNpmFamily(client),
    clientMessage,
  });
}
