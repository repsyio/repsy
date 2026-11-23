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
 * RPS-1435: Cargo and Go never replace a published version, so their settings do not show
 * `allowOverride`, and a PUT that sends it (the panel form does, for every protocol) is answered
 * 200 with the column left alone instead of being rejected.
 *
 * `ProtocolRepoControllerIT` (Settings) proves the whole settings PUT in more detail (field by field,
 * `releases`/`snapshots` refused outside Maven and NuGet, RPS-1200 and RPS-1210), so only these two
 * tests stay here: Repsy Cloud runs this file and pins them as known gaps.
 *
 * Repsy Cloud still shows `allowOverride` for them (RPS-1577): its harness pins the two tests below as
 * known gaps of the targets that have not caught up (`PanelBackend.knownTestGap`, README "Known gaps"),
 * keyed `skeleton > repo-settings > allowOverride is omitted from the settings and ignored on write
 * (RPS-1435, CARGO)` and `... GOLANG)`. A gap is not written in this spec, so it is removed on the
 * Cloud side, in the change that bumps the fix.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

for (const repoType of [RepoType.CARGO, RepoType.GOLANG]) {
  test(`allowOverride is omitted from the settings and ignored on write (RPS-1435, ${repoType})`, async ({
    seeder,
    panelApi,
  }) => {
    const repo = await seeder.createRepo(repoType, { privateRepo: true });
    expect(await panelApi.getSettings(repo.name)).not.toHaveProperty('allowOverride');

    await panelApi.updateSettings(repo.name, { allowOverride: false, privateRepo: false });

    const settings = await panelApi.getSettings(repo.name);
    expect(settings).not.toHaveProperty('allowOverride');
    expect(settings.privateRepo).toBe(false);
  });
}
