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
 * The scenario-driven cargo suite (step 3b, RPS-294): `registerPublishConsumeLoop(cargoAdapter)`
 * wires the whole shared catalog into cargo, exactly like `tests/npm/publish-consume.spec.ts` does
 * for npm. The catalog's own crate names are underscore-only (`cargoAdapter.packageName`,
 * `cargo-raw.ts`'s `crateName`), so the loop itself never exercises the hyphenated-name path
 * below -- this file's own hand-built-`World` test does, the cargo analogue of npm's scoped-package
 * test and maven's RPS-1196 test.
 */
import { RepoType } from '../../src/api/panel-api.js';
import * as cargo from '../../src/clients/cargo.js';
import { cargoAdapter } from '../../src/clients/cargo.js';
import { adminCredential, parseIndex, rawGetIndex } from '../../src/clients/cargo-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { Coordinates, World } from '../../src/scenarios/world.js';

registerPublishConsumeLoop(cargoAdapter);

/**
 * A real `cargo publish`/`cargo fetch` round trip of a HYPHENATED crate name (`e2e-<runid>-hyphen`).
 *
 * RPS-1212 (fixed): `CrateUtils.normalizeCrateName` (lower-case, `-` -> `_`) is applied for
 * lookup/storage paths, but the served sparse-index entry's `name` field now comes from
 * `CargoCrate.originalName` -- the spelling the crate was actually published under -- so a
 * hyphenated crate is served, and resolved, under its own name. `cargo publish` confirms on its
 * first post-publish index poll (`wait_for_any_publish_confirmation`) instead of burning the whole
 * `CARGO_PUBLISH_TIMEOUT` window, and a real `cargo fetch` no longer refuses the dependency with
 * "no matching package" (it cross-checks the served entry's own `name` field against the dependency
 * name it declared).
 */
test(
  'cargo > hyphenated crate name real client round trip',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
    const credential = adminCredential();
    const packageName = `e2e-${seeder.runId}-hyphen`;
    const target: Coordinates = { packageName, version: cargoAdapter.version('release') };
    const scenario: Scenario = {
      id: 'hyphen-crate-name',
      tags: ['@smoke'],
      repo: { privateRepo: true },
      credential: 'admin-password',
      expect: { publish: 'ok', consume: 'ok' },
    };
    const world: World = {
      scenario,
      protocol: 'cargo',
      repoName: repo.name,
      credential,
      publishTarget: target,
      consumeTarget: target,
    };

    const published = await cargo.publish(world);
    expect(
      published.outcome,
      `publish: expected "ok", got "${published.outcome}" (http ${published.httpStatus}; cargo ` +
        `exit ${published.clientExitCode}; ${published.command})`,
    ).toBe('ok');
    expect(published.clientExitCode, `cargo publish: ${published.command}`).toBe(0);

    const indexRes = await rawGetIndex(repo.name, credential, packageName);
    expect(indexRes.status, 'the sparse index has an entry for this crate').toBe(200);
    const entries = parseIndex(indexRes.body);
    const entry = entries.find((e) => e.vers === target.version);
    expect(entry, `an index entry for version "${target.version}"`).toBeDefined();

    // RPS-1212 (fixed): the served entry names the crate as it was actually published, not the
    // normalised lookup spelling.
    expect(
      entry?.name,
      `the sparse index should serve "${packageName}" (the published spelling), got ` +
        `"${entry?.name}"`,
    ).toBe(packageName);

    const resolved = await cargo.resolve(world);
    expect(
      resolved.outcome,
      `resolve: the auth-only sparse-index GET should still succeed (http ${resolved.httpStatus})`,
    ).toBe('ok');

    // A real `cargo fetch` cross-checks the index entry's own `name` field against the dependency
    // name it declared ("e2e-<runid>-hyphen"); now that the entry names the published spelling, the
    // fetch resolves the hyphenated dependency instead of refusing it with "no matching package".
    expect(resolved.clientExitCode, `cargo fetch: ${resolved.command}`).toBe(0);
    expect(
      resolved.contentSha256,
      `the resolved crate (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
    ).toBe(published.contentSha256);
  },
);
