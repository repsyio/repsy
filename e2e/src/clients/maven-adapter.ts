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
 * `mavenAdapter`: maven's `ProtocolAdapter` (`scenarios/adapter.ts`), the object
 * `tests/maven/publish-consume.spec.ts` hands to `scenarios/loop.ts`'s `registerPublishConsumeLoop`
 * (step 3a, RPS-294). `publish`/`resolve`/`seedPublish` are `clients/maven.ts`'s real-client
 * functions unchanged; `fingerprint`/`expectNothingStored`/`afterSuccessfulRoundTrip` wrap the
 * repo-tree walk (`maven-raw.ts`) and the checks moved to `maven-checks.ts`.
 */
import { publish, resolve, seedPublish } from './maven.js';
import { repoTree, type RepoTree } from './maven-raw.js';
import { expectNothingStored, expectSnapshotFollowedThroughMetadata } from './maven-checks.js';
import { slugify } from '../scenarios/coordinates.js';
import type { ProtocolAdapter } from '../scenarios/adapter.js';
import type { Scenario } from '../scenarios/types.js';

let versionSeq = 0;

/** A version unique to this test process, in `versionType`'s form. Not a coordinate by itself. */
function uniqueVersion(versionType: 'release' | 'snapshot'): string {
  versionSeq += 1;
  const base = `0.0.${Date.now()}${versionSeq}`;
  return versionType === 'snapshot' ? `${base}-SNAPSHOT` : base;
}

export const mavenAdapter: ProtocolAdapter<RepoTree> = {
  protocol: 'maven',
  client: { name: 'mvn', publishVerb: 'deploy', consumeVerb: 'dependency:get' },

  packageName: (runId: string, scenario: Scenario) =>
    `io.repsy.e2e.${runId}:maven-${slugify(scenario.id)}`,
  version: uniqueVersion,

  publish,
  resolve,
  seedPublish,

  fingerprint: (world) => repoTree(world.repoName),
  expectNothingStored,

  afterSuccessfulRoundTrip: async (world, _published, resolved) => {
    if (world.scenario.versionType === 'snapshot') {
      await expectSnapshotFollowedThroughMetadata(world, world.scenario, resolved);
    }
  },
};
