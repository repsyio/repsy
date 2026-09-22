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
 * The scenario-driven npm suite (step 3a, RPS-294): `registerPublishConsumeLoop(npmAdapter)` wires
 * the whole shared catalog into npm, exactly like `tests/maven/publish-consume.spec.ts` does for
 * maven. Every scenario whose `consume` expectation is `'ok'` currently surfaces RPS-1205 (`npm
 * install` cannot fetch the tarball on Repsy OS) via `npmAdapter.knownConsumeFailure`, so those
 * scenarios report as an *expected* failure, not a plain pass or a plain fail -- see `scenarios/
 * loop.ts`'s file header. The auth/override/version-kind outcome itself is still asserted for real.
 */
import { RepoType } from '../../src/api/panel-api.js';
import * as npm from '../../src/clients/npm.js';
import { npmAdapter } from '../../src/clients/npm.js';
import { adminCredential, rawGetPath, tarballPath } from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, World } from '../../src/scenarios/world.js';

registerPublishConsumeLoop(npmAdapter);

/**
 * A real `npm publish`/`npm install` round trip of a SCOPED package (`@e2e-<runid>/scoped`), the npm
 * analogue of maven's RPS-1196 hand-built-`World` test: scope handling is exactly where a
 * path-construction bug like RPS-1205 tends to also bite (the scope is a real path segment in the
 * canonical tarball URL, but is dropped from the tarball's own file name -- see `npm-raw.ts`'s file
 * header), so this is worth its own direct check beyond the catalog loop's unscoped packages.
 *
 * The publish half is expected to succeed outright. The consume half is expected to fail exactly the
 * way RPS-1205 fails every other package on this instance (`test.fail`, not a plain assertion): the
 * real `npm install` cannot fetch the tarball, even though the auth-only packument GET succeeds and
 * the tarball is genuinely sitting in storage at its canonical path, which this test proves directly.
 */
test(
  'npm > scoped package real client round trip (RPS-1205)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
    const credential = adminCredential();
    const packageName = `@e2e-${seeder.runId}/scoped`;
    const target: Coordinates = { packageName, version: npmAdapter.version('release') };
    const scenario: Scenario = {
      id: 'scoped-package',
      tags: ['@smoke'],
      repo: { privateRepo: true },
      credential: 'admin-password',
      expect: { publish: 'ok', consume: 'ok' },
    };
    const world: World = {
      scenario,
      protocol: 'npm',
      repoName: repo.name,
      credential,
      publishTarget: target,
      consumeTarget: target,
    };

    const published = await npm.publish(world);
    expect(
      published.outcome,
      `publish: expected "ok", got "${published.outcome}" (http ${published.httpStatus}; npm exit ` +
        `${published.clientExitCode}; ${published.command})`,
    ).toBe('ok');
    expect(published.clientExitCode, `npm publish: ${published.command}`).toBe(0);

    // The tarball is genuinely in storage at its canonical path, byte for byte what was published --
    // this is what makes RPS-1205 a URL-construction bug and not a storage one.
    const canonical = await rawGetPath(
      repo.name,
      credential,
      tarballPath(packageName, target.version),
    );
    expect(canonical.status, 'the tarball is stored at its canonical path').toBe(200);

    const resolved = await npm.resolve(world);
    expect(
      resolved.outcome,
      `resolve: the auth-only packument GET should still succeed (http ${resolved.httpStatus})`,
    ).toBe('ok');

    test.fail(
      true,
      'RPS-1205: npm install cannot fetch the tarball (fixTarballUrl misrewrites the path for the ' +
        'OS single-tenant layout), scoped packages included',
    );
    expect(resolved.clientExitCode, `npm install: ${resolved.command}`).toBe(0);
    expect(
      resolved.contentSha256,
      `the resolved package (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
    ).toBe(published.contentSha256);
  },
);
