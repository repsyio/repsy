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
 * RPS-1498: a target may be Repsy Cloud, and the scenario engine copes without a fork of the catalog:
 * different capabilities, credentials the target cannot seed (skip), outcomes that differ from the
 * catalog's (`expectByTarget`) and scenario sides pinned as known gaps (`knownGap`). Nothing here talks
 * to a server: `fake-cloud-panel-backend.ts` is a fake cloud backend module, and the loop runs a fake
 * protocol adapter against it, so the real `registerPublishConsumeLoop` and `world` fixture are what
 * is exercised.
 *
 * The tests of the last `describe` include three that are skipped on purpose: they ARE the skip
 * paths (a credential the capabilities rule out, one the backend refuses, and a `@cloud-skip`
 * scenario), and their skip reasons show in the report. A pinned gap that starts passing fails its
 * test with Playwright's "Expected to fail, but passed", which is what forces the flip in the same
 * change as a bump; that failure is checked by hand (README "Targets"), not here, since a test that
 * must fail cannot be part of a green suite. The two gap tests print `✘` in Playwright's list
 * reporter: they are EXPECTED failures and count as passed.
 */
import { resolve } from 'node:path';

import { BACKEND_MODULE_ENV, resolvePanelBackendFactory } from '../../src/api/backend-registry.js';
import type { AdapterResult, ProtocolAdapter } from '../../src/scenarios/adapter.js';
import { env } from '../../src/env.js';
import {
  expect,
  test,
  tryMaterializeCredentialKind,
  unsupportedCredentialReason,
} from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';
import {
  expectationFor,
  type Outcome,
  type Scenario,
  type ScenarioExpectation,
} from '../../src/scenarios/types.js';
import { Seeder } from '../../src/seed/seeder.js';
import { capabilitiesFor } from '../../src/target.js';
import { RepoType } from '../../src/api/panel-backend.js';
import {
  FAKE_CLOUD_CAPABILITIES,
  FAKE_CLOUD_GAP_TICKET,
  FAKE_CLOUD_OVERLAY,
  FAKE_CLOUD_UNSUPPORTED_TICKET,
  FakeCloudPanelBackend,
} from './fake-cloud-panel-backend.js';

const FAKE_CLOUD_MODULE = resolve(import.meta.dirname, 'fake-cloud-panel-backend.ts');

test.describe('target capabilities', () => {
  test('the OS targets keep the capabilities the harness always assumed', () => {
    for (const name of ['local', 'ci', 'remote'] as const) {
      const os = capabilitiesFor(name);

      expect(os.kind, name).toBe('os');
      expect(os.urlScheme, name).toBe('repo');
      expect(os.supportsUserRole, name).toBe(true);
      expect(os.supportsRepoUsers, name).toBe(false);
      expect(os.supportsExpiredTokenSeed, name).toBe(true);
      expect(os.expiredTokenStrategy, name).toBe('past-date');
      expect(os.maxDeployTokensPerRepo, name).toBe(Number.POSITIVE_INFINITY);
      expect(os.supportsDirectoryListing, name).toBe(true);
    }
    expect(capabilitiesFor('local')).toMatchObject({ ownsStack: true, isRemote: false });
    expect(capabilitiesFor('ci')).toMatchObject({ ownsStack: true, isRemote: false });
    expect(capabilitiesFor('remote')).toMatchObject({ ownsStack: false, isRemote: true });
  });

  test('only Maven and NuGet take the releases/snapshots settings, on every target', () => {
    for (const name of ['local', 'remote', 'cloud-remote', 'cloud-local'] as const) {
      const caps = capabilitiesFor(name);

      for (const type of Object.values(RepoType)) {
        expect(caps.supportsVersionAllowanceSettings(type), `${name} ${type}`).toBe(
          type === 'MAVEN' || type === 'NUGET',
        );
      }
    }
  });

  test('both Repsy Cloud targets are remote cloud targets on the FREE plan', () => {
    for (const name of ['cloud-remote', 'cloud-local'] as const) {
      const cloud = capabilitiesFor(name);

      expect(cloud.kind, name).toBe('cloud');
      expect(cloud.urlScheme, name).toBe('owner-repo');
      // Not owned and not tunable, so the loop runs @negative scenarios serially on a budget.
      expect(cloud, name).toMatchObject({
        ownsStack: false,
        canTuneThrottle: false,
        isRemote: true,
      });
      expect(cloud.maxDeployTokensPerRepo, name).toBe(1);
      expect(cloud.supportsRepoUsers, name).toBe(true);
      expect(cloud.supportsUserRole, name).toBe(false);
    }
  });

  test('the token capabilities agree with each other on every target', () => {
    for (const name of ['local', 'ci', 'remote', 'cloud-remote', 'cloud-local'] as const) {
      const caps = capabilitiesFor(name);

      // A past expiration date is only a strategy when the target accepts one.
      expect(caps.expiredTokenStrategy !== 'past-date' || caps.supportsExpiredTokenSeed, name).toBe(
        true,
      );
      // Some user model has to exist, or `user-password` could never be seeded.
      expect(caps.supportsUserRole || caps.supportsRepoUsers, name).toBe(true);
    }
  });

  test('a Repsy Cloud target without a backend module says which variable to set', async () => {
    const previous = { target: env.target, module: process.env[BACKEND_MODULE_ENV] };
    delete process.env[BACKEND_MODULE_ENV];
    env.target = 'cloud-local';
    try {
      await expect(resolvePanelBackendFactory()).rejects.toThrow(BACKEND_MODULE_ENV);
    } finally {
      env.target = previous.target;
      // An empty value counts as unset (backend-registry.ts).
      process.env[BACKEND_MODULE_ENV] = previous.module ?? '';
    }
  });
});

test.describe('expectation overlay', () => {
  const scenario: Scenario = {
    id: 'fake-overlay-protocol',
    tags: [],
    repo: { privateRepo: true },
    credential: 'token-ro',
    expect: { publish: 'forbidden', consume: 'ok' },
    expectByProtocol: { npm: { consume: 'unauthorized' } },
  };

  test('without an overlay the result is what it was before the overlay existed', () => {
    expect(expectationFor(scenario, 'maven')).toEqual({ publish: 'forbidden', consume: 'ok' });
    expect(expectationFor(scenario, 'npm')).toEqual({
      publish: 'forbidden',
      consume: 'unauthorized',
    });
    expect(expectationFor(scenario, 'maven', {})).toEqual(expectationFor(scenario, 'maven'));
  });

  test('the target overlay beats the protocol override, and a protocol entry beats the wildcard', () => {
    // `*` for every protocol, `maven` only for maven (see FAKE_CLOUD_OVERLAY).
    expect(expectationFor(scenario, 'maven', FAKE_CLOUD_OVERLAY)).toEqual({
      publish: 'conflict',
      consume: 'ok',
    });
    expect(expectationFor(scenario, 'npm', FAKE_CLOUD_OVERLAY)).toEqual({
      publish: 'unauthorized',
      consume: 'unauthorized',
    });
  });

  test('an overlay names only the sides it changes, and only for its own scenario', () => {
    const other: Scenario = { ...scenario, id: 'not-in-the-overlay' };
    const overlay = { 'fake-overlay-protocol': { '*': { consume: 'rejected' as Outcome } } };

    expect(expectationFor(scenario, 'maven', overlay)).toEqual({
      publish: 'forbidden',
      consume: 'rejected',
    } satisfies ScenarioExpectation);
    expect(expectationFor(other, 'maven', overlay)).toEqual(expectationFor(other, 'maven'));
  });
});

test.describe('credentials a target cannot seed', () => {
  test('a credential is ruled out from the capabilities alone', () => {
    expect(unsupportedCredentialReason('token-expired', FAKE_CLOUD_CAPABILITIES)).toContain(
      'expiredTokenStrategy',
    );
    expect(
      unsupportedCredentialReason('token-expired', capabilitiesFor('cloud-local')),
    ).toBeUndefined();
    expect(unsupportedCredentialReason('token-expired', capabilitiesFor('local'))).toBeUndefined();
    expect(
      unsupportedCredentialReason('user-password', {
        ...capabilitiesFor('cloud-local'),
        supportsRepoUsers: false,
      }),
    ).toContain('no user credential');
    expect(unsupportedCredentialReason('token-rw', FAKE_CLOUD_CAPABILITIES)).toBeUndefined();
  });

  test('a backend that refuses one turns into a skip reason, not a failure', async () => {
    const backend = new FakeCloudPanelBackend(env.apiBaseUrl);
    const seeder = new Seeder(backend, 'fake02');
    const repo = await seeder.createRepo(RepoType.MAVEN);

    const user = await tryMaterializeCredentialKind(
      seeder,
      'user-password',
      repo.name,
      RepoType.MAVEN,
      FAKE_CLOUD_CAPABILITIES,
    );
    const expired = await tryMaterializeCredentialKind(
      seeder,
      'token-expired',
      repo.name,
      RepoType.MAVEN,
      FAKE_CLOUD_CAPABILITIES,
    );

    // The backend was asked and said no (its ticket is in the reason) ...
    expect(user.skipReason).toContain(FAKE_CLOUD_UNSUPPORTED_TICKET);
    expect(user.skipReason).toContain('seedUserCredential');
    // ... the other was decided from the capabilities: the fake throws if it is asked.
    expect(expired.skipReason).toContain('expiredTokenStrategy');
    expect(backend.tokens.size).toBe(0);

    await seeder.cleanup();
  });

  test('a credential the backend can seed is seeded, and cleanup removes it', async () => {
    const backend = new FakeCloudPanelBackend(env.apiBaseUrl);
    const seeder = new Seeder(backend, 'fake03');
    const repo = await seeder.createRepo(RepoType.MAVEN);

    const rw = await tryMaterializeCredentialKind(
      seeder,
      'token-rw',
      repo.name,
      RepoType.MAVEN,
      FAKE_CLOUD_CAPABILITIES,
    );

    expect(rw.skipReason).toBeUndefined();
    expect(rw.credential).toMatchObject({ transport: 'basic', kind: 'token' });
    expect(backend.tokens.size).toBe(1);

    await seeder.cleanup();

    expect(backend.tokens.size).toBe(0);
  });
});

test.describe('known gaps of a backend', () => {
  test('a gap is pinned to one side of one scenario, with its ticket', () => {
    const backend = new FakeCloudPanelBackend(env.apiBaseUrl);

    expect(backend.knownGap('maven', 'fake-gap-consume', 'consume')).toContain(
      FAKE_CLOUD_GAP_TICKET,
    );
    expect(backend.knownGap('maven', 'fake-gap-consume', 'publish')).toBeUndefined();
    expect(backend.knownGap('maven', 'fake-plain', 'consume')).toBeUndefined();
  });
});

/**
 * A protocol adapter that talks to nothing: what the fake cloud "answers" for each scenario is a
 * table. The scenario loop, the `world` fixture and the backend are the real ones.
 */
const CLOUD_PUBLISH: Readonly<Record<string, Outcome>> = {
  // The catalog says `forbidden` for both; the fake cloud answers as the overlay pins.
  'fake-overlay': 'unauthorized',
  'fake-overlay-protocol': 'conflict',
  // A cloud bug: the publish is refused although it must succeed (a known gap).
  'fake-gap-publish': 'rejected',
};
const CLOUD_CONSUME: Readonly<Record<string, Outcome>> = {
  // A cloud bug: the consume is refused although it must succeed (a known gap).
  'fake-gap-consume': 'forbidden',
};
const FAKE_HTTP_STATUS: Record<Outcome, number> = {
  ok: 200,
  unauthorized: 401,
  forbidden: 403,
  conflict: 409,
  rejected: 400,
};

function answer(outcome: Outcome, command: string): AdapterResult {
  return {
    outcome,
    httpStatus: FAKE_HTTP_STATUS[outcome],
    clientExitCode: outcome === 'ok' ? 0 : 1,
    command,
    contentSha256: 'fake-sha256',
  };
}

const fakeAdapter: ProtocolAdapter<number> = {
  // `maven`: a protocol the fixtures know (a repo type), titled `maven[fake-cloud] > <id>`.
  protocol: 'maven',
  label: 'fake-cloud',
  tags: ['@fake-cloud'],
  client: { name: 'fake', publishVerb: 'publish', consumeVerb: 'resolve' },
  packageName: (runId) => `fake:${runId}`,
  version: () => '1.0.0',
  publish: async (w) => answer(CLOUD_PUBLISH[w.scenario.id] ?? 'ok', 'fake publish'),
  resolve: async (w) => answer(CLOUD_CONSUME[w.scenario.id] ?? 'ok', 'fake resolve'),
  seedPublish: async () => ({ contentSha256: 'fake-sha256' }),
  fingerprint: async () => 0,
  expectNothingStored: async (_world, before) => {
    expect(before).toBe(0);
  },
};

const scenario = (
  id: string,
  credential: Scenario['credential'],
  expectation: ScenarioExpectation,
  tags: Scenario['tags'] = [],
): Scenario => ({
  id,
  tags,
  repo: { privateRepo: true },
  credential,
  expect: expectation,
});

const ROUND_TRIP: ScenarioExpectation = { publish: 'ok', consume: 'ok' };
const READ_ONLY: ScenarioExpectation = { publish: 'forbidden', consume: 'ok' };

const FAKE_CLOUD_SCENARIOS: readonly Scenario[] = [
  // Nothing special: proves a fake cloud round trip passes.
  scenario('fake-plain', 'token-rw', ROUND_TRIP),
  // The overlay: the catalog expects `forbidden`, the cloud answers `unauthorized`. Also a @negative
  // scenario, which on a remote target runs serially on the RemoteAuthBudget.
  scenario('fake-overlay', 'token-ro', READ_ONLY, ['@negative']),
  scenario('fake-overlay-protocol', 'token-ro', READ_ONLY),
  // Known gaps: each is expected to fail, so it reports as an expected failure.
  scenario('fake-gap-publish', 'token-rw', ROUND_TRIP),
  scenario('fake-gap-consume', 'token-rw', ROUND_TRIP),
  // Skips: rules out by capability, refused by the backend, and tagged.
  scenario('fake-skip-expired-token', 'token-expired', { publish: 'unauthorized', consume: 'ok' }),
  scenario('fake-skip-user-refused', 'user-password', ROUND_TRIP),
  scenario('fake-skip-tagged', 'token-rw', ROUND_TRIP, ['@cloud-skip']),
];

test.describe('a fake Repsy Cloud target in the scenario loop', () => {
  let previousModule: string | undefined;

  // Runs in the worker before any test of the block sets its fixtures up, so `panelApi` resolves the
  // fake cloud module by itself, as a cloud run does (see backend-module.spec.ts).
  test.beforeAll(() => {
    previousModule = process.env[BACKEND_MODULE_ENV];
    process.env[BACKEND_MODULE_ENV] = FAKE_CLOUD_MODULE;
  });

  test.afterAll(() => {
    if (previousModule === undefined) {
      delete process.env[BACKEND_MODULE_ENV];
    } else {
      process.env[BACKEND_MODULE_ENV] = previousModule;
    }
  });

  test.use({ targetCapabilities: FAKE_CLOUD_CAPABILITIES });

  registerPublishConsumeLoop(fakeAdapter, {
    scenarios: FAKE_CLOUD_SCENARIOS,
    target: FAKE_CLOUD_CAPABILITIES,
  });
});
