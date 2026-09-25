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
 * `ivyAdapter`: the Apache Ivy client's `ProtocolAdapter` (`scenarios/adapter.ts`, RPS-135).
 * `tests/maven/ivy.spec.ts` hands it to `scenarios/loop.ts`'s `registerPublishConsumeLoop`, so the whole
 * shared catalog runs against an ordinary Maven repository with a real `ant ivy:publish` and a real
 * `ant ivy:retrieve`. `publish`/`resolve`/`seedPublish` are `clients/ivy.ts`'s real-client functions;
 * the repo-tree fingerprint and the "nothing stored" check are Maven's own, since the repository is the
 * same.
 *
 * `packageName` is `groupId:artifactId` with the group as Ivy's organisation and the artifactId as its
 * module, so every raw helper that builds a path from the world's coordinates (`repoTree`,
 * `expectNothingStored`, the probes) points at the files Ivy stores.
 */
import { slugify } from '../scenarios/coordinates.js';
import type { ProtocolAdapter } from '../scenarios/adapter.js';
import type { Scenario } from '../scenarios/types.js';
import { publish, resolve, seedPublish } from './ivy.js';
import { expectPublishStored } from './ivy-checks.js';
import { uniqueVersion } from './maven-adapter.js';
import { expectNothingStored } from './maven-checks.js';
import { repoTree, type RepoTree } from './maven-raw.js';
import { expectLiteralSnapshotStored } from './sbt-checks.js';

export const ivyAdapter: ProtocolAdapter<RepoTree> = {
  protocol: 'ivy',
  client: { name: 'ant', publishVerb: 'ivy:publish', consumeVerb: 'ivy:retrieve' },

  packageName: (runId: string, scenario: Scenario) =>
    `io.repsy.e2e.${runId}:ivy-${slugify(scenario.id)}`,
  version: uniqueVersion,

  publish: (world) => publish(world),
  resolve: (world) => resolve(world),
  seedPublish: (world) => seedPublish(world),

  fingerprint: (world) => repoTree(world.repoName),
  expectNothingStored,

  afterSuccessfulRoundTrip: async (world, published, resolved) => {
    await expectPublishStored(world, published);
    if (world.scenario.versionType === 'snapshot') {
      await expectLiteralSnapshotStored(world, resolved);
    }
  },
};
