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
 * RPS-1481: where the spec-driven tests (`src/api/spec-ops.ts`: the contract specs, the role sweep) find
 * `openapi-spec.yaml`. `REPSY_E2E_OPENAPI_SPEC` names it (the setting the runner's client generation reads);
 * without it the checkout's file is used. When there is none a Repsy Cloud target skips, with the reason,
 * and every other target fails: an OS run never skips silently. The skip and the failure are outcomes of a
 * test, so they are proven in inner runs (`inner-run.ts`) whose reports are asserted.
 */
import { resolve } from 'node:path';

import { expect, test } from '@playwright/test';

import { locateSpec, specMissingMessage } from '../../src/api/spec-ops.js';
import { runInner } from './inner-run.js';

const NO_SUCH_SPEC = '/no/such/dir/openapi-spec.yaml';
const CHECKOUT_SPEC = resolve(
  import.meta.dirname,
  '../../../repsy-backend/src/main/resources/openapi/openapi-spec.yaml',
);

test.describe('locating the spec', () => {
  test('the setting is the spec, absolute or relative to the working directory, and nothing else is tried', () => {
    const present = (path: string) => path.endsWith('mine.yaml') || path.endsWith('checkout.yaml');

    expect(locateSpec('/etc/specs/mine.yaml', present)).toEqual({
      found: '/etc/specs/mine.yaml',
      looked: ['/etc/specs/mine.yaml'],
    });
    expect(locateSpec('specs/mine.yaml', present)).toEqual({
      found: resolve(process.cwd(), 'specs/mine.yaml'),
      looked: [resolve(process.cwd(), 'specs/mine.yaml')],
    });
    // A setting that points nowhere is an absent spec: no fallback to the checkout's own file.
    expect(locateSpec('/etc/specs/other.yaml', present)).toEqual({
      found: undefined,
      looked: ['/etc/specs/other.yaml'],
    });
  });

  test('without the setting the checkout is looked in, then the runner mount', () => {
    for (const unset of [undefined, '', '  ']) {
      const { found, looked } = locateSpec(unset, () => false);
      expect(found, JSON.stringify(unset)).toBeUndefined();
      expect(looked).toHaveLength(3);
      expect(looked[0]).toBe(CHECKOUT_SPEC);
      expect(looked[1]).toBe('/repsy-backend/src/main/resources/openapi/openapi-spec.yaml');
    }
    expect(locateSpec(undefined, (path) => path === CHECKOUT_SPEC).found).toBe(CHECKOUT_SPEC);
  });

  test('the message names where it looked and the setting that fixes it', () => {
    const message = specMissingMessage(['/a', '/b']);
    expect(message).toContain('/a, /b');
    expect(message).toContain('REPSY_E2E_OPENAPI_SPEC');
  });
});

test.describe('a spec that is absent, in a run of its own', () => {
  test.describe.configure({ timeout: 120_000 });
  const absent = { REPSY_E2E_OPENAPI_SPEC: NO_SUCH_SPEC };

  test('a Repsy Cloud target skips the test and the file that read it while loading', () => {
    for (const target of ['cloud-local', 'cloud-remote']) {
      const r = runInner(target, absent);
      expect(r.get('spec path')?.status, target).toBe('skipped');
      expect(r.get('operations read at load')?.status, target).toBe('skipped');
    }
  });

  test('an OS target fails: a missing spec is never a silent skip', () => {
    for (const target of ['local', 'remote']) {
      const r = runInner(target, absent, ['known-gap-cases']);
      expect(r.get('spec path'), target).toMatchObject({ status: 'unexpected', ran: 'failed' });
    }
  });

  test('an OS target cannot even load a file that reads the spec while loading', () => {
    for (const target of ['local', 'remote']) {
      // The file does not load, so the run holds no test of it at all: not skipped, not passed.
      expect(runInner(target, absent, ['spec-at-load']).size, target).toBe(0);
    }
  });

  test('a target that has the spec runs the tests, whichever it is', () => {
    for (const target of ['local', 'cloud-local']) {
      const r = runInner(target, { REPSY_E2E_OPENAPI_SPEC: CHECKOUT_SPEC });
      expect(r.get('spec path')?.status, target).toBe('expected');
      expect(r.get('operations read at load')?.status, target).toBe('expected');
    }
  });
});
