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
 * `cargo-raw.ts`'s `crateName`), so the loop itself never exercises the hyphen/normalisation
 * candidate bug below -- this file's own hand-built-`World` test does, the cargo analogue of npm's
 * scoped-package test and maven's RPS-1196 test.
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
 * A real `cargo publish`/`cargo fetch` round trip of a HYPHENATED crate name (`e2e-<runid>-hyphen`),
 * confirmed live (see `README.md`'s "H2"): `CrateUtils.normalizeCrateName` (lower-case, `-` -> `_`)
 * is applied both when a crate is stored AND when the sparse index is looked up (a raw HTTP GET of
 * either spelling answers 200), so the served entry always names the NORMALISED crate
 * ("e2e_<runid>_hyphen"), never the hyphenated name it was published under. `cargo publish` itself
 * still exits 0, but slowly (confirmed live: ~30s here, vs. well under a second for the catalog
 * loop's underscore-only names): its own post-publish index poll
 * (`wait_for_any_publish_confirmation`) apparently does not accept the server's answer as a match for
 * the hyphenated name it just sent, so it burns the whole `CARGO_PUBLISH_TIMEOUT` window before
 * falling back to its "timed out waiting for ... to be available" WARNING (not a failure -- the
 * publish itself already succeeded server-side). A real `cargo fetch` then does NOT trust the file
 * location the way the raw probe does: it cross-checks the served entry's own `name` field against
 * the dependency name it declared and refuses outright with "no matching package" -- confirmed live,
 * not just predicted; see the `test.fail` below.
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

    // RPS-1212 (confirmed live, see README.md's "H2"): the served entry names the
    // NORMALISED crate, not the one this test published under.
    expect(
      entry?.name,
      `RPS-1212: the sparse index serves "${entry?.name}" for a crate published as ` +
        `"${packageName}" -- CrateUtils.normalizeCrateName is applied when the crate is stored`,
    ).not.toBe(packageName);
    expect(entry?.name).toBe(packageName.replace(/-/g, '_'));

    const resolved = await cargo.resolve(world);
    expect(
      resolved.outcome,
      `resolve: the auth-only sparse-index GET should still succeed (http ${resolved.httpStatus})`,
    ).toBe('ok');

    // Confirmed live: a real `cargo fetch` cross-checks the index entry's own `name` field against
    // the dependency name it declared ("e2e-<runid>-hyphen") and refuses when they disagree ("no
    // matching package named ... found"), even though the raw sparse-index GET above succeeded. This
    // is the real-client consequence of the same candidate bug pinned above.
    test.fail(
      true,
      'RPS-1212: a real `cargo fetch` refuses a hyphenated dependency ("no matching ' +
        'package") because the served index entry names the normalised crate instead',
    );
    expect(resolved.clientExitCode, `cargo fetch: ${resolved.command}`).toBe(0);
    expect(
      resolved.contentSha256,
      `the resolved crate (${resolved.resolvedFile ?? 'none found'}) is not the published one`,
    ).toBe(published.contentSha256);
  },
);
