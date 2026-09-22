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
 * RPS-1200: `PUT /api/repos/{repoName}/settings` applies settings field by field. A field left out
 * of the request body (or sent as JSON `null`) must keep its current value; only a field that is
 * actually present, non-null, in the body is changed. This is proven end to end (real HTTP, real
 * database) rather than at the service-method level, which `ProtocolRepoControllerIT` already
 * covers in more detail.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

for (const repoType of [RepoType.NPM, RepoType.MAVEN]) {
  test(`a settings PUT with a single field changed leaves every other field alone (${repoType})`, async ({
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(repoType, { privateRepo: true });

    // A known, non-default pattern across all five fields, so nothing here is a creation default.
    await seeder.setSettings(repo.name, {
      privateRepo: true,
      allowOverride: false,
      releases: true,
      snapshots: false,
      securityScanEnabled: true,
    });

    // Only allowOverride is sent, flipped from its current value; every other field is omitted.
    await panelApi.updateSettings(repo.name, { allowOverride: true });

    const settings = await panelApi.getSettings(repo.name);
    expect(settings.allowOverride).toBe(true);
    expect(settings.privateRepo).toBe(true);
    expect(settings.releases).toBe(true);
    expect(settings.snapshots).toBe(false);
    expect(settings.securityScanEnabled).toBe(true);
  });
}
