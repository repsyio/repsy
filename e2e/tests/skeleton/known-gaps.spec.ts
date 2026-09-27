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
 * RPS-1510: the known-gap mechanism for specs outside the scenario catalog (`src/known-gaps.ts`, README
 * "Known gaps"). The pure parts are tested here directly. The behaviour that matters, a pinned gap that
 * still fails is an expected failure, and a pinned gap that STOPS failing fails the run, cannot sit in a
 * green suite as a test that fails, so `known-gap-inner/known-gap-cases.inner.ts` is run as an inner
 * Playwright run under each target, against the fake Repsy Cloud backend, and its report is asserted:
 * the flip-and-fail is proven on every run of this spec, not by hand.
 */
import { expect, test } from '@playwright/test';

import {
  byTarget,
  CLOUD_SKIP_TAG,
  defineKnownGaps,
  isCloudSkipped,
  knownGapOn,
  testGapKey,
} from '../../src/known-gaps.js';
import { capabilitiesFor } from '../../src/target.js';
import { runInner } from './inner-run.js';

test.describe('the registry', () => {
  const gaps = defineKnownGaps([
    { key: 'a > b', jira: 'RPS-1', reason: 'r1', targets: ['cloud-local'] },
    { key: 'a > b', jira: 'RPS-2', reason: 'r2', targets: ['cloud-remote'] },
  ]);

  test('a gap is looked up by its exact key and its target, and names its ticket', () => {
    expect(gaps.lookup('a > b', 'cloud-local')).toBe('RPS-1: r1');
    expect(gaps.lookup('a > b', 'cloud-remote')).toBe('RPS-2: r2');
    expect(gaps.lookup('a > b', 'cloud-local')).not.toContain('r2');
    // The registry of an OS run answers nothing: no entry names an OS target.
    for (const os of ['local', 'ci', 'remote'] as const) {
      expect(gaps.lookup('a > b', os)).toBeUndefined();
    }
    expect(gaps.lookup('a', 'cloud-local')).toBeUndefined();
    expect(gaps.lookup('a > b > c', 'cloud-local')).toBeUndefined();
  });

  test('an entry without a ticket, a reason or a target, and a duplicate, are refused at load', () => {
    const entry = { key: 'k', jira: 'RPS-3', reason: 'r', targets: ['cloud-local'] } as const;

    expect(() => defineKnownGaps([{ ...entry, jira: 'maven > x' }])).toThrow(/jira must be/);
    expect(() => defineKnownGaps([{ ...entry, jira: 'RPS-' }])).toThrow(/jira must be/);
    expect(() => defineKnownGaps([{ ...entry, reason: ' ' }])).toThrow(/no reason/);
    expect(() => defineKnownGaps([{ ...entry, targets: [] }])).toThrow(/no target/);
    expect(() => defineKnownGaps([entry, entry])).toThrow(/listed twice for cloud-local/);
    expect(() => defineKnownGaps([entry, { ...entry, targets: ['cloud-remote'] }])).not.toThrow();
  });
});

test.describe('keys, tags and values by target', () => {
  test('the key of a test is its project, file stem and titles', ({}, testInfo) => {
    expect(testGapKey(testInfo)).toBe(
      'skeleton > known-gaps > keys, tags and values by target > the key of a test is its project, file stem and titles',
    );
  });

  test('@cloud-skip skips on the Repsy Cloud targets only', () => {
    for (const cloud of ['cloud-remote', 'cloud-local'] as const) {
      expect(isCloudSkipped([CLOUD_SKIP_TAG], cloud), cloud).toBe(true);
      expect(isCloudSkipped(['@smoke'], cloud), cloud).toBe(false);
    }
    for (const os of ['local', 'ci', 'remote'] as const) {
      expect(isCloudSkipped([CLOUD_SKIP_TAG], os), os).toBe(false);
    }
  });

  test('knownGapOn does nothing on a target it does not name, and refuses a bad ticket', () => {
    expect(knownGapOn('cloud-remote', 'RPS-9', 'r', 'local')).toBe(false);
    expect(knownGapOn(['cloud-remote', 'cloud-local'], 'RPS-9', 'r', 'remote')).toBe(false);
    expect(() => knownGapOn('cloud-local', 'no ticket', 'r', 'local')).toThrow(/jira must be/);
  });

  test('byTarget prefers the target, then its product, then the default', () => {
    const values = { default: 'd', cloud: 'c', 'cloud-local': 'cl', os: 'o' };

    expect(byTarget(values, 'cloud-local')).toBe('cl');
    expect(byTarget(values, 'cloud-remote')).toBe('c');
    expect(byTarget(values, 'local')).toBe('o');
    expect(byTarget({ default: 'd', cloud: 'c' }, 'remote')).toBe('d');
    // A falsy value is a value.
    expect(byTarget({ default: 1, 'cloud-local': 0 }, 'cloud-local')).toBe(0);
  });

  test('the OS targets have no gaps and the two cloud targets differ only in what is owned', () => {
    // RPS-1510 (D7): cloud-local is not remote (its own stack, nobody else on it), and neither cloud
    // target is owned or tunable.
    expect(capabilitiesFor('cloud-local')).toMatchObject({
      ownsStack: false,
      canTuneThrottle: false,
      isRemote: false,
    });
    expect(capabilitiesFor('cloud-remote')).toMatchObject({
      ownsStack: false,
      canTuneThrottle: false,
      isRemote: true,
    });
  });
});

test.describe('the mechanism, in a run of its own', () => {
  test.describe.configure({ timeout: 120_000 });

  test('on cloud-local a pinned gap that still fails is an expected failure, a fixed one fails the run', () => {
    const r = runInner('cloud-local');

    // Expected failures: reported "expected", with the ticket as the annotation.
    expect(r.get('gap that still fails')).toMatchObject({ status: 'expected' });
    expect(r.get('gap that still fails')?.annotations).toContain(
      'RPS-0003: the fake cloud gets this wrong',
    );
    expect(r.get('a group > nested gap that still fails')?.status).toBe('expected');
    // The key a spec picks, and the gap the spec writes itself.
    expect(r.get('explicit key of a feature')?.status).toBe('expected');
    expect(r.get('inline gap of cloud-local')?.status).toBe('expected');
    expect(r.get('inline gap of cloud-local')?.annotations).toContain(
      'RPS-9999: written in the test',
    );

    // FLIP-AND-FAIL: the gap stopped failing, so the run fails and says why.
    // (Playwright's message is "Expected to fail, but passed": a test pinned as failing that passed.)
    expect(r.get('gap that was fixed')).toMatchObject({
      status: 'unexpected',
      expected: 'failed',
      ran: 'passed',
    });

    // Nothing else is affected: not pinned for this target, not pinned at all, and the explicit key
    // nobody pinned.
    expect(r.get('gap of cloud-remote only')?.status).toBe('expected');
    expect(r.get('gap of cloud-remote only')?.annotations).toEqual([]);
    expect(r.get('no gap at all')?.status).toBe('expected');
    expect(r.get('explicit key that is no gap')?.status).toBe('expected');
    expect(r.get('a value by target')?.status).toBe('expected');
  });

  test('a gap is pinned per target: cloud-remote pins what cloud-local does not', () => {
    const r = runInner('cloud-remote');

    expect(r.get('gap of cloud-remote only')?.status).toBe('expected');
    expect(r.get('gap of cloud-remote only')?.annotations).toContain(
      'RPS-0005: only the older fake cloud gets this wrong',
    );
    // The inline gap names cloud-local only: on cloud-remote the test fails for real.
    expect(r.get('inline gap of cloud-local')?.status).toBe('unexpected');
    expect(r.get('gap that was fixed')?.status).toBe('unexpected');
  });

  test('@cloud-skip is skipped on a cloud target, whatever the consumer config', () => {
    for (const target of ['cloud-local', 'cloud-remote']) {
      expect(runInner(target).get('tagged cloud-skip')?.status, target).toBe('skipped');
    }
  });

  test('on an OS target the registry is empty: nothing is pinned, nothing is skipped', () => {
    // The same fake module, but no entry of it names an OS target: every test runs as it is written.
    const r = runInner('local');

    expect(r.get('gap that still fails')?.status).toBe('unexpected');
    expect(r.get('gap that was fixed')?.status).toBe('expected');
    expect(r.get('gap that was fixed')?.annotations).toEqual([]);
    expect(r.get('explicit key of a feature')?.status).toBe('unexpected');
    expect(r.get('inline gap of cloud-local')?.status).toBe('unexpected');
    // `@cloud-skip` is a Repsy Cloud tag: on Repsy OS the test runs (and here it throws on purpose).
    expect(r.get('tagged cloud-skip')?.status).toBe('unexpected');
    expect(r.get('a value by target')?.status).toBe('expected');
  });
});
