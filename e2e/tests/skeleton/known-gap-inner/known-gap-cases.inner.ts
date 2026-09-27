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
 * Cases for `../known-gaps.spec.ts`, which runs this file as an inner Playwright run under a chosen
 * `REPSY_TARGET`, against the fake Repsy Cloud backend (`../fake-cloud-panel-backend.ts`, whose
 * `FAKE_CLOUD_TEST_GAPS` pins some of these titles). It is not a `*.spec.ts`, so the `skeleton` project never
 * runs it by itself: several of these tests are MEANT to fail or to be reported as expected failures.
 */
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { byTarget, knownGapOn } from '../../../src/known-gaps.js';

test('gap that still fails', () => {
  throw new Error('the defect of the target');
});

test('gap that was fixed', () => {
  expect(1 + 1).toBe(2);
});

// Pinned on cloud-remote only: it fails there, and passes on every other target.
test('gap of cloud-remote only', () => {
  if (env.target === 'cloud-remote') {
    throw new Error('the defect of cloud-remote');
  }
});

test('no gap at all', () => {
  expect(env.target).toBeTruthy();
});

test('explicit key of a feature', ({ knownGap }) => {
  knownGap('feature > partial-put');
  throw new Error('the defect of the feature');
});

test('explicit key that is no gap', ({ knownGap }) => {
  knownGap('feature > not-pinned');
});

test('inline gap of cloud-local', () => {
  knownGapOn('cloud-local', 'RPS-9999', 'written in the test');
  throw new Error('the defect of cloud-local');
});

test('tagged cloud-skip', { tag: ['@cloud-skip'] }, () => {
  throw new Error('a @cloud-skip test must not run on a Repsy Cloud target');
});

test('a value by target', () => {
  expect(byTarget({ default: 'os', cloud: 'cloud', 'cloud-local': 'local-cloud' })).toBe(
    { local: 'os', ci: 'os', remote: 'os', 'cloud-remote': 'cloud', 'cloud-local': 'local-cloud' }[
      env.target
    ],
  );
});

test.describe('a group', () => {
  test('nested gap that still fails', () => {
    throw new Error('the defect of the target');
  });
});
