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
 * The npm server's registry rules, pinned at the protocol level with raw HTTP PUTs/GETs (no `npm`
 * client), the npm analogue of the Maven raw-HTTP rules (now `MavenArtifactPathIT` and its siblings). Every status/msgId here was read
 * from `AbstractNpmProtocolFacade.publish`/`NpmPackageUtils.extractVersionNameFromPayload` first and
 * then confirmed against a running instance (see this repo's e2e run report for the raw evidence):
 *
 *  - A malformed/invalid semver version string is refused with 400 `invalidPackageVersion`
 *    (`NpmPackageUtils.extractVersionNameFromPayload`, before anything is read from the payload beyond
 *    the version name) and stores nothing.
 *
 * The override refusal (`NpmPublishOverrideRefusedIT`), the revoked Bearer token
 * (`AuthThrottleIT`) and the `dist.tarball` rules of RPS-1205 and RPS-1333 (`NpmPublishProtocolIT`) are
 * integration tests now (RPS-2738).
 */
import { ERROR_CODES } from '../../src/error-codes.js';
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  buildPublishDocument,
  rawGetPackument,
  rawPublish,
  type RawResponse,
} from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  packageName: string;
}

/** A fresh npm repo (permissive defaults) and a run-unique, unscoped package name for it. */
async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
  return { repoName: repo.name, packageName: `e2e-${seeder.runId}-${label}` };
}

function fakeTarball(seed: string): Buffer {
  // Repsy never validates that a stored "tarball" is actually a valid tar+gzip stream (the same way
  // maven's raw seeds use plain strings as "jar" bytes), so arbitrary bytes
  // are enough for a raw-only probe.
  return Buffer.from(`fake npm tarball ${seed}`, 'utf8');
}

function expectPut(
  res: RawResponse,
  status: number,
  msgId: string | undefined,
  what: string,
): void {
  expect(res.status, `${what}: PUT answered ${res.status} ${res.msgId ?? ''}`).toBe(status);
  expect(res.msgId, `${what}: the error message id`).toBe(msgId);
}

test.describe('npm registry rules (raw HTTP)', () => {
  test(
    'an invalid version string is refused and stores nothing',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'badversion');
      const admin = adminCredential();

      const doc = buildPublishDocument({
        repoName: layout.repoName,
        packageName: layout.packageName,
        version: 'not-a-semver-version',
        tarballBytes: fakeTarball('bad'),
      });
      expectPut(
        await rawPublish(layout.repoName, admin, layout.packageName, doc),
        400,
        ERROR_CODES.INVALID_PACKAGE_VERSION,
        'malformed version string',
      );

      const packument = await rawGetPackument(layout.repoName, admin, layout.packageName);
      expect(packument.status, 'nothing was ever published for this package').toBe(404);
    },
  );
});
