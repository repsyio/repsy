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
 * `gradleAdapter`: the Gradle client's `ProtocolAdapter` (`scenarios/adapter.ts`), one per build
 * file language (RPS-133). `tests/maven/gradle-groovy.spec.ts` and `gradle-kotlin.spec.ts` hand
 * `gradleAdapter('groovy')` and `gradleAdapter('kotlin')` to `scenarios/loop.ts`'s
 * `registerPublishConsumeLoop`, so the whole shared catalog runs once per DSL against an ordinary
 * Maven repository. `publish`/`resolve`/`seedPublish` are `clients/gradle.ts`'s real-client functions;
 * the repo-tree fingerprint and the "nothing stored" check are Maven's own, since the repository is
 * the same.
 */
import { repoTree, type RepoTree } from './maven-raw.js';
import { expectNothingStored, expectSnapshotFollowedThroughMetadata } from './maven-checks.js';
import { uniqueVersion } from './maven-adapter.js';
import { publish, resolve, seedPublish, type GradleDsl } from './gradle.js';
import { slugify } from '../scenarios/coordinates.js';
import type { ProtocolAdapter } from '../scenarios/adapter.js';
import type { Scenario } from '../scenarios/types.js';

/** The protocol key of a DSL's adapter: `gradle-groovy` | `gradle-kotlin`. */
export function gradleProtocol(dsl: GradleDsl): `gradle-${GradleDsl}` {
  return `gradle-${dsl}`;
}

/** What a Gradle publish lists in a SNAPSHOT's version-level metadata: the jar, the POM and, unlike
 *  Maven, the Gradle module metadata (the sources jar shares the `jar` extension). */
const GRADLE_SNAPSHOT_CHECK = {
  extensions: ['jar', 'module', 'pom'],
  resolvedFileKeepsSnapshotName: true,
} as const;

export function gradleAdapter(dsl: GradleDsl): ProtocolAdapter<RepoTree> {
  return {
    protocol: gradleProtocol(dsl),
    client: { name: 'gradle', publishVerb: 'publish', consumeVerb: 'fetchDependencies' },

    packageName: (runId: string, scenario: Scenario) =>
      `io.repsy.e2e.${runId}:gradle-${dsl}-${slugify(scenario.id)}`,
    version: uniqueVersion,

    publish: (world) => publish(world, { dsl }),
    resolve: (world) => resolve(world, { dsl }),
    seedPublish: (world) => seedPublish(world, { dsl }),

    fingerprint: (world) => repoTree(world.repoName),
    expectNothingStored,

    afterSuccessfulRoundTrip: async (world, _published, resolved) => {
      if (world.scenario.versionType === 'snapshot') {
        await expectSnapshotFollowedThroughMetadata(
          world,
          world.scenario,
          resolved,
          GRADLE_SNAPSHOT_CHECK,
        );
      }
    },
  };
}
