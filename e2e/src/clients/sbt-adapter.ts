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
 * `sbtAdapter`: the sbt client's `ProtocolAdapter` (`scenarios/adapter.ts`, RPS-134).
 * `tests/maven/sbt.spec.ts` hands it to `scenarios/loop.ts`'s `registerPublishConsumeLoop`, so the whole
 * shared catalog runs against an ordinary Maven repository with a real `sbt publish` and a real sbt
 * dependency resolution. `publish`/`resolve`/`seedPublish` are `clients/sbt.ts`'s real-client
 * functions; the repo-tree fingerprint and the "nothing stored" check are Maven's own, since the
 * repository is the same.
 *
 * `packageName` is the artifactId sbt PUBLISHES (`sbt-<scenario>_2.13`, the cross-version suffix
 * included), not the `name` in the build: that keeps every raw helper that builds a path from the
 * world's coordinates (`repoTree`, `expectNothingStored`, the probes) pointing at the real files.
 */
import { slugify } from '../scenarios/coordinates.js';
import type { ProtocolAdapter } from '../scenarios/adapter.js';
import type { Scenario } from '../scenarios/types.js';
import { uniqueVersion } from './maven-adapter.js';
import { expectNothingStored } from './maven-checks.js';
import { repoTree, type RepoTree } from './maven-raw.js';
import { expectLiteralSnapshotStored } from './sbt-checks.js';
import { crossSuffix, publish, resolve, SCALA_213, seedPublish } from './sbt.js';

export const sbtAdapter: ProtocolAdapter<RepoTree> = {
  protocol: 'sbt',
  client: { name: 'sbt', publishVerb: 'publish', consumeVerb: 'fetchDependencies' },

  packageName: (runId: string, scenario: Scenario) =>
    `io.repsy.e2e.${runId}:sbt-${slugify(scenario.id)}${crossSuffix(SCALA_213)}`,
  version: uniqueVersion,

  publish: (world) => publish(world),
  resolve: (world) => resolve(world),
  seedPublish: (world) => seedPublish(world),

  fingerprint: (world) => repoTree(world.repoName),
  expectNothingStored,

  afterSuccessfulRoundTrip: async (world, _published, resolved) => {
    if (world.scenario.versionType === 'snapshot') {
      await expectLiteralSnapshotStored(world, resolved);
    }
  },
};
