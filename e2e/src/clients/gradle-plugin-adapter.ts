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
 * `gradlePluginAdapter`: the Gradle plugin flow's `ProtocolAdapter` (RPS-133), one per build file
 * language. Handed to `scenarios/loop.ts`'s `registerPublishConsumeLoop`, it runs the shared catalog
 * with a plugin as the artifact: publishing a plugin to Repsy and applying it from
 * `pluginManagement { repositories }`. SNAPSHOT plugins are left out (the catalog's snapshot scenarios
 * do not list these protocols): the flow is about how a plugin id is found, not about SNAPSHOT
 * resolution, which the library adapter (`gradle-adapter.ts`) already covers.
 */
import { uniqueVersion } from './maven-adapter.js';
import { repoTree, type RepoTree } from './maven-raw.js';
import { expectNothingStored } from './maven-checks.js';
import { publish, resolve, seedPublish } from './gradle-plugin.js';
import type { GradleDsl } from './gradle.js';
import { slugify } from '../scenarios/coordinates.js';
import type { ProtocolAdapter } from '../scenarios/adapter.js';
import type { Scenario } from '../scenarios/types.js';

/** The protocol key of a DSL's plugin adapter: `gradle-plugin-groovy` | `gradle-plugin-kotlin`. */
export function gradlePluginProtocol(dsl: GradleDsl): `gradle-plugin-${GradleDsl}` {
  return `gradle-plugin-${dsl}`;
}

export function gradlePluginAdapter(dsl: GradleDsl): ProtocolAdapter<RepoTree> {
  return {
    protocol: gradlePluginProtocol(dsl),
    client: { name: 'gradle', publishVerb: 'publish', consumeVerb: 'e2eMarker (apply the plugin)' },

    packageName: (runId: string, scenario: Scenario) =>
      `io.repsy.e2e.${runId}:gradle-plugin-${dsl}-${slugify(scenario.id)}`,
    version: uniqueVersion,

    publish: (world) => publish(world, { dsl }),
    resolve: (world) => resolve(world, { dsl }),
    seedPublish: (world) => seedPublish(world, { dsl }),

    fingerprint: (world) => repoTree(world.repoName),
    expectNothingStored,
  };
}
