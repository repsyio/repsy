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
 * RPS-1495: the panel backend is chosen at run time, so another target (Repsy Cloud) can supply the
 * panel operations without touching the seeder, the fixtures, the sweep or the specs
 * (`src/api/backend-registry.ts`, README "Targets").
 *
 * `fake-panel-backend.ts` is an in-memory backend that talks to no server. These tests point
 * `REPSY_E2E_BACKEND_MODULE` at it and check that the registry, the `seeder` and `panelApi` fixtures
 * (which resolve the backend themselves, because Playwright's workers re-import the spec files and no
 * registration made in the config would reach them) and `UnsupportedPanelOperation` all work through
 * the interface alone. Nothing here calls Repsy.
 */
import { resolve } from 'node:path';

import { BACKEND_MODULE_ENV, createPanelBackend } from '../../src/api/backend-registry.js';
import { OsPanelBackend } from '../../src/api/os-panel-backend.js';
import {
  isUnsupportedPanelOperation,
  RepoType,
  UnsupportedPanelOperation,
} from '../../src/api/panel-backend.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { Seeder } from '../../src/seed/seeder.js';
import { FAKE_UNSUPPORTED_TICKET, FakePanelBackend } from './fake-panel-backend.js';

const FAKE_MODULE = resolve(import.meta.dirname, 'fake-panel-backend.ts');

/** Runs `body` with `REPSY_E2E_BACKEND_MODULE` set (or unset), then puts the previous value back. */
async function withBackendModule<T>(value: string | undefined, body: () => Promise<T>): Promise<T> {
  const previous = process.env[BACKEND_MODULE_ENV];
  if (value === undefined) {
    delete process.env[BACKEND_MODULE_ENV];
  } else {
    process.env[BACKEND_MODULE_ENV] = value;
  }
  try {
    return await body();
  } finally {
    if (previous === undefined) {
      delete process.env[BACKEND_MODULE_ENV];
    } else {
      process.env[BACKEND_MODULE_ENV] = previous;
    }
  }
}

test.describe('panel backend registry', () => {
  test(
    'REPSY_E2E_BACKEND_MODULE names the backend instead of the built-in one',
    { tag: ['@smoke'] },
    async () => {
      const backend = await withBackendModule(FAKE_MODULE, () =>
        createPanelBackend('http://panel.invalid'),
      );

      expect(backend).toBeInstanceOf(FakePanelBackend);
      expect((backend as FakePanelBackend).baseUrl).toBe('http://panel.invalid');
    },
  );

  test('without it the built-in Repsy OS backend is used', async () => {
    const backend = await withBackendModule(undefined, () => createPanelBackend());

    expect(backend).toBeInstanceOf(OsPanelBackend);
  });

  test('an empty value counts as unset', async () => {
    const backend = await withBackendModule('', () => createPanelBackend());

    expect(backend).toBeInstanceOf(OsPanelBackend);
  });

  test('a module that cannot be imported fails and names the variable', async () => {
    const attempt = withBackendModule('./no-such-backend-module.ts', () => createPanelBackend());

    await expect(attempt).rejects.toThrow(BACKEND_MODULE_ENV);
  });

  test('a module without a factory is refused', async () => {
    const noFactory = resolve(import.meta.dirname, '../../src/target.ts');
    const attempt = withBackendModule(noFactory, () => createPanelBackend());

    await expect(attempt).rejects.toThrow(/createPanelBackend/);
  });

  test('the seeder works through the interface alone', async () => {
    const backend = new FakePanelBackend(env.apiBaseUrl);
    const seeder = new Seeder(backend, 'fake01');

    const user = await seeder.createUser();
    const repo = await seeder.createRepo(RepoType.MAVEN);
    expect(backend.users.has(user.id)).toBe(true);
    expect(backend.repos.get(repo.name)).toBe(RepoType.MAVEN);

    await seeder.cleanup();

    expect(backend.users.size).toBe(0);
    expect(backend.repos.size).toBe(0);
  });

  test('an operation the backend lacks throws UnsupportedPanelOperation with its ticket', async () => {
    const backend = new FakePanelBackend(env.apiBaseUrl);

    const err = await backend.getDockerImageSummary().catch((caught: unknown) => caught);

    expect(isUnsupportedPanelOperation(err)).toBe(true);
    expect(err).toBeInstanceOf(UnsupportedPanelOperation);
    expect((err as UnsupportedPanelOperation).ticket).toBe(FAKE_UNSUPPORTED_TICKET);
    expect((err as Error).message).toContain('getDockerImageSummary');
  });
});

test.describe('fixtures with an external backend', () => {
  let previous: string | undefined;

  // beforeAll runs before any test-scoped fixture of the tests below is set up, in the worker that
  // runs them, so `panelApi` resolves the backend module by itself: exactly what a cloud run does.
  test.beforeAll(() => {
    previous = process.env[BACKEND_MODULE_ENV];
    process.env[BACKEND_MODULE_ENV] = FAKE_MODULE;
  });

  test.afterAll(() => {
    if (previous === undefined) {
      delete process.env[BACKEND_MODULE_ENV];
    } else {
      process.env[BACKEND_MODULE_ENV] = previous;
    }
  });

  test('the panelApi and seeder fixtures use the module named by the variable', async ({
    panelApi,
    seeder,
  }) => {
    expect(panelApi).toBeInstanceOf(FakePanelBackend);

    const repo = await seeder.createRepo(RepoType.NPM);
    expect(await panelApi.listAllRepos({ q: repo.name })).toHaveLength(1);

    await seeder.cleanup();

    expect(await panelApi.listAllRepos({ q: repo.name })).toHaveLength(0);
  });
});
