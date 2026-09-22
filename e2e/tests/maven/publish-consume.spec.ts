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
 * The scenario-driven maven suite. Step 3a (RPS-294) generalised the scenario loop itself out of
 * this file into `scenarios/loop.ts`'s `registerPublishConsumeLoop`, reusable by every protocol; this
 * file now only wires maven's own adapter (`clients/maven-adapter.ts`) into that loop, plus one real
 * -client test the loop's catalog-driven model cannot express (below).
 */
import { RepoType } from '../../src/api/panel-api.js';
import * as maven from '../../src/clients/maven.js';
import { mavenAdapter } from '../../src/clients/maven-adapter.js';
import { adminCredential } from '../../src/clients/maven-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, World } from '../../src/scenarios/world.js';

registerPublishConsumeLoop(mavenAdapter);

/**
 * A real `mvn deploy`/`dependency:get` round trip for an artifactId that itself contains ".pom"
 * (RPS-1196). Not a `catalog.ts` scenario: `fixtures.ts`'s `world` fixture slugifies the scenario id
 * into the packageName (`slugify` strips dots), so it can never produce an artifactId with a literal
 * "." in it. The `World` is built by hand instead, the way `clients/maven.ts`'s `deploy`/`resolve`
 * need it (only `scenario.id`, for the isolated work directory, `protocol`, `repoName`, `credential`,
 * `publishTarget` and `consumeTarget`).
 *
 * Against the pre-RPS-1196 image this fails with a non-zero `mvn deploy` exit code (the jar its
 * `deploy` goal PUTs first is parsed as a POM and refused, 400 `malformedPomFile`), the exact
 * discrepancy the harness exists to catch: a raw POM PUT under the same artifactId answers 200 (see
 * `upload-rules.spec.ts`'s own RPS-1196 test), because only the artifact's *other* files are
 * casualties of the whole-path substring bug.
 */
test(
  'maven > dotted-artifactId real client round trip (RPS-1196)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
    const credential = adminCredential();
    const target: Coordinates = {
      packageName: `io.repsy.e2e.${seeder.runId}:e2e.pom.utils`,
      version: `0.0.${Date.now()}`,
    };
    const scenario: Scenario = {
      id: 'dotted-artifact-id',
      tags: ['@smoke'],
      repo: { privateRepo: true },
      credential: 'admin-password',
      expect: { publish: 'ok', consume: 'ok' },
    };
    const world: World = {
      scenario,
      protocol: 'maven',
      repoName: repo.name,
      credential,
      publishTarget: target,
      consumeTarget: target,
    };

    const published = await maven.publish(world);
    expect(
      published.outcome,
      `publish: expected "ok", got "${published.outcome}" (http ${published.httpStatus}; mvn exit ` +
        `${published.clientExitCode}; ${published.command})`,
    ).toBe('ok');
    expect(published.clientExitCode, `mvn deploy: ${published.command}`).toBe(0);

    const resolved = await maven.resolve(world);
    expect(
      resolved.outcome,
      `resolve: expected "ok", got "${resolved.outcome}" (http ${resolved.httpStatus}; mvn exit ` +
        `${resolved.clientExitCode}; ${resolved.command})`,
    ).toBe('ok');
    expect(resolved.clientExitCode, `mvn dependency:get: ${resolved.command}`).toBe(0);
    expect(
      resolved.contentSha256,
      `the resolved jar (${resolved.resolvedFile ?? 'none found'}) is not the deployed one`,
    ).toBe(published.contentSha256);
  },
);
