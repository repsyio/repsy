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
 * The scenario-driven maven suite (plan section "Client adapters", the `for (const s of
 * scenariosFor('maven'))` loop): every catalog scenario that applies to maven publishes and then
 * consumes with the scenario's credential, asserting the `Outcome` the catalog pins (`catalog.ts` --
 * every one of those was pinned by probing a running instance, not assumed from the plan).
 *
 * Beyond the pinned `Outcome`, every scenario also checks what a status alone cannot: the real `mvn`
 * exit code agrees with the outcome (a refused deploy fails the client, an accepted one does not);
 * a refused publish left the repository exactly as it was (nothing stored, nothing half-written);
 * and a consumer that succeeded got the very jar that was deployed (the latest accepted deploy, or
 * the pre-published one when the scenario's own publish was refused). A SNAPSHOT that was deployed
 * and resolved is additionally followed through its version-level `maven-metadata.xml`.
 *
 * Remote hardening (plan section "Execution targets", "Remote specifics"): on a `remote` target,
 * `@local-only` scenarios are skipped (none exist in this catalog yet, but the guard is here for
 * when one does), and `@negative` scenarios run serially, each reserving a slot from
 * `RemoteAuthBudget` first, so their failed-auth attempts stay under the server's own throttle
 * instead of tripping it for every client behind the same address.
 */
import * as maven from '../../src/clients/maven.js';
import type { AdapterResult } from '../../src/clients/maven.js';
import {
  adminCredential,
  artifactDir,
  baseVersion,
  parseArtifactVersions,
  parseListing,
  parseVersionMetadata,
  rawGet,
  repoTree,
  type RepoTree,
  splitPackageName,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { RemoteAuthBudget } from '../../src/scenarios/remote-throttle.js';
import { SCENARIOS } from '../../src/scenarios/catalog.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Scenario } from '../../src/scenarios/types.js';
import { scenariosFor, type Outcome } from '../../src/scenarios/types.js';
import { target } from '../../src/target.js';
import type { World } from '../../src/scenarios/world.js';

const PROTOCOL = 'maven';

const remoteAuthBudget = new RemoteAuthBudget();

function expectOutcome(result: AdapterResult, expected: Outcome): void {
  expect(
    result.outcome,
    `expected "${expected}", got "${result.outcome}" (http ${result.httpStatus}; mvn exit ` +
      `${result.clientExitCode}; ${result.command})`,
  ).toBe(expected);
}

/**
 * The real client's exit code must agree with the outcome the raw HTTP status gave: a refused
 * publish or a refused resolve fails `mvn`, an accepted one does not. It cannot say *which* refusal
 * it was, but it catches a raw probe and a real client disagreeing about what the server did.
 */
function expectClientAgrees(result: AdapterResult, expected: Outcome, what: string): void {
  const shouldSucceed = expected === 'ok';
  expect(
    result.clientExitCode === 0,
    `the real mvn ${what} should ${shouldSucceed ? 'succeed' : 'fail'} for "${expected}" ` +
      `(mvn exit ${result.clientExitCode}; http ${result.httpStatus}; ${result.command})`,
  ).toBe(shouldSucceed);
}

/**
 * After a refused publish: the repository is byte-for-byte what it was before (so nothing was
 * stored and nothing was overwritten, metadata included), and a version that did not exist before
 * still does not, anywhere: no version directory, no version-level metadata, no files, and not
 * listed in the artifact-level metadata (which may legitimately exist for another version).
 */
async function expectNothingStored(w: World, before: RepoTree): Promise<void> {
  expect(
    await repoTree(w.repoName),
    'a refused deploy must leave the repository exactly as it was',
  ).toEqual(before);

  const [groupId, artifactId] = splitPackageName(w.publishTarget.packageName);
  const version = w.publishTarget.version;
  const dir = versionDir(groupId, artifactId, version);

  if (Object.keys(before).some((path) => path.startsWith(`${dir}/`))) {
    return; // A redeploy: the version existed, the tree comparison above is the whole check.
  }

  const admin = adminCredential();
  for (const path of [
    `${dir}/`,
    `${dir}/maven-metadata.xml`,
    `${dir}/${artifactId}-${version}.pom`,
    `${dir}/${artifactId}-${version}.jar`,
  ]) {
    const res = await rawGet(w.repoName, admin, path);
    expect(res.status, `GET ${path} after the refused deploy`).toBe(404);
  }

  const artifactMetadata = await rawGet(
    w.repoName,
    admin,
    `${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
  );
  if (artifactMetadata.status === 200) {
    expect(parseArtifactVersions(artifactMetadata.body.toString('utf8'))).not.toContain(version);
  } else {
    expect(artifactMetadata.status).toBe(404);
  }
}

/**
 * A consumer that succeeded got the jar of the latest accepted deploy of the coordinate: this
 * test's own publish if it was accepted, else the one the fixture pre-published (a refused redeploy
 * must not have changed what consumers get).
 */
function expectResolvedContent(w: World, published: AdapterResult, resolved: AdapterResult): void {
  const expected = published.outcome === 'ok' ? published.contentSha256 : w.seeded?.contentSha256;
  expect(expected, 'no digest of the deployed jar to compare the resolved one with').toBeDefined();
  expect(
    resolved.contentSha256,
    `the resolved jar (${resolved.resolvedFile ?? 'none found'}) is not the deployed one`,
  ).toBe(expected);
}

/**
 * A SNAPSHOT that was deployed (once, or twice for a redeploy scenario) and resolved: the
 * version-level metadata names the latest build, the consumer resolved exactly that timestamped
 * jar, every deploy's timestamped jar is still stored, and the artifact-level metadata lists the
 * version once.
 */
async function expectSnapshotFollowedThroughMetadata(
  w: World,
  scenario: Scenario,
  resolved: AdapterResult,
): Promise<void> {
  const [groupId, artifactId] = splitPackageName(w.consumeTarget.packageName);
  const version = w.consumeTarget.version;
  const dir = versionDir(groupId, artifactId, version);
  const deploys = scenario.reuseCoordinates ? 2 : 1;
  const admin = adminCredential();

  const metadataRes = await rawGet(w.repoName, admin, `${dir}/maven-metadata.xml`);
  expect(metadataRes.status, `GET ${dir}/maven-metadata.xml`).toBe(200);
  const metadata = parseVersionMetadata(metadataRes.body.toString('utf8'));
  expect(metadata.version).toBe(version);
  expect(metadata.buildNumber, 'the buildNumber counts the deploys of this SNAPSHOT').toBe(deploys);

  const latest = `${baseVersion(version)}-${metadata.timestamp}-${metadata.buildNumber}`;
  expect(metadata.snapshotVersions).toEqual({ pom: latest, jar: latest });
  expect(resolved.resolvedFile, 'the consumer resolves the latest timestamped jar').toBe(
    `${artifactId}-${latest}.jar`,
  );

  const listing = await rawGet(w.repoName, admin, `${dir}/`);
  const buildNumbers = parseListing(listing.body.toString('utf8'))
    .map((entry) => /-\d{8}\.\d{6}-(\d+)\.jar$/.exec(entry)?.[1])
    .filter((buildNumber) => buildNumber !== undefined)
    .map(Number);
  expect(buildNumbers, 'every deploy keeps its own timestamped jar').toEqual(
    Array.from({ length: deploys }, (_, i) => i + 1),
  );

  const artifactMetadata = await rawGet(
    w.repoName,
    admin,
    `${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
  );
  expect(parseArtifactVersions(artifactMetadata.body.toString('utf8'))).toEqual([version]);
}

/** Publishes, then resolves, and checks everything the catalog pins for `scenario`. */
async function runScenario(w: World, scenario: Scenario): Promise<void> {
  // The state a refused publish must leave untouched: taken after the fixture seeded and
  // configured the repo, right before the scenario's own publish.
  const before = scenario.expect.publish === 'ok' ? undefined : await repoTree(w.repoName);

  const published = await maven.publish(w);
  expectOutcome(published, scenario.expect.publish);
  expectClientAgrees(published, scenario.expect.publish, 'deploy');
  if (before) {
    await expectNothingStored(w, before);
  }

  const resolved = await maven.resolve(w);
  expectOutcome(resolved, scenario.expect.consume);
  expectClientAgrees(resolved, scenario.expect.consume, 'dependency:get');

  if (scenario.expect.consume === 'ok') {
    expectResolvedContent(w, published, resolved);
  }
  if (
    scenario.versionType === 'snapshot' &&
    scenario.expect.publish === 'ok' &&
    scenario.expect.consume === 'ok'
  ) {
    await expectSnapshotFollowedThroughMetadata(w, scenario, resolved);
  }
}

function registerScenario(scenario: Scenario): void {
  test(`maven > ${scenario.id}`, { tag: [...scenario.tags] }, async ({ world }) => {
    test.skip(
      target.isRemote && scenario.tags.includes('@local-only'),
      'a @local-only scenario is skipped on a remote target',
    );

    if (target.isRemote && scenario.tags.includes('@negative')) {
      await remoteAuthBudget.reserve();
    }

    await runScenario(await world(scenario, PROTOCOL), scenario);
  });
}

const scenarios = scenariosFor(SCENARIOS, PROTOCOL);
const negativeScenarios = scenarios.filter((scenario) => scenario.tags.includes('@negative'));
const otherScenarios = scenarios.filter((scenario) => !scenario.tags.includes('@negative'));

test.describe('maven publish/consume', () => {
  for (const scenario of otherScenarios) {
    registerScenario(scenario);
  }
});

test.describe('maven publish/consume (negative)', () => {
  // Parallel on local/ci (the stack's own throttle is raised for exactly this, see
  // docker-compose.stack.yml); serial on remote, where it cannot be, so these scenarios' failed-auth
  // attempts spend the RemoteAuthBudget one at a time instead of bursting together.
  test.describe.configure({ mode: target.isRemote ? 'serial' : 'parallel' });

  for (const scenario of negativeScenarios) {
    registerScenario(scenario);
  }
});
