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
 * Known gaps of a target, for any spec (RPS-1510; README "Known gaps"). The scenario catalog has its own
 * hook (`PanelBackend.knownGap(protocol, scenarioId, side)`, `scenarios/loop.ts`); a spec outside the
 * catalog (`repo-settings`, `registry-rules`, a protocol's own suite...) had none. This is it.
 *
 * A known gap is a difference of one target from the behaviour a spec pins, already filed as a Jira ticket,
 * that the harness records instead of hiding. The test is marked `test.fail`, so:
 *
 *  - while the gap exists the test FAILS inside, and Playwright reports it as an expected failure (passed);
 *  - when the gap is closed the test passes, and Playwright fails it with "Expected to fail, but passed".
 *    That is the point: the entry is deleted in the change that brought the fix (the bump), so the pin
 *    never outlives the bug. It is a pin, not a skip.
 *
 * Gaps are TARGET-scoped (`targets`): Repsy Cloud DEV (`cloud-remote`) runs an older build than a Repsy Cloud
 * stack built next to the harness (`cloud-local`), so one gap is open on one and closed on the other. A gap
 * listed for no target of the current run does nothing, and the registry of an OS run is empty by
 * construction: nothing here changes what `local`, `ci` or `remote` do.
 *
 * Three ways in, all ending in `test.fail(true, "<RPS-nnnn>: <reason>")`:
 *  1. A registry (`defineKnownGaps`) that the backend module serves as `PanelBackend.knownTestGap`. The
 *     `test` of `scenarios/fixtures.ts` consults it for every test, by its `testGapKey`. Repsy Cloud keeps
 *     its registry in its own repository, next to the backend, so a gap is added and removed there.
 *  2. `knownGap(key)`, the fixture of the same `test`: the same lookup by a key the spec chooses, for a
 *     gap that belongs to a feature and not to one test title.
 *  3. `knownGapOn(targets, jira, reason)`: a gap written in the spec itself, for a spec on the bare
 *     `@playwright/test` `test` (no fixtures) or one that must carry its own pin.
 *
 * `test.fail` marks the WHOLE test. A gap that is a different answer (a status, a message id) is better an
 * `expectByTarget` overlay (scenarios) or a `byTarget` value (below): the assertion stays green and the
 * difference stays visible. Use a gap for what is a defect of the target.
 */
import { test, type TestInfo } from '@playwright/test';

import { env, type RepsyTarget } from './env.js';
import { capabilitiesFor } from './target.js';

/** The pattern of a Jira key: every gap names its ticket, and the ticket lives under RPS-1451 for Repsy Cloud. */
const JIRA_KEY = /^RPS-\d+$/;

/** One known gap of a target. */
export interface KnownGap {
  /** What it pins, `<project> > <file stem> > <describe titles > test title>`, or a key the spec picks. */
  key: string;
  /** The Jira ticket that tracks it; filed before the gap is pinned. */
  jira: string;
  /** What is wrong on the target, one line. */
  reason: string;
  /** The targets it is open on. A gap that is open on none of the run's target does nothing. */
  targets: readonly RepsyTarget[];
}

/** A validated set of gaps, and the lookup a backend serves as `PanelBackend.knownTestGap`. */
export interface KnownGapRegistry {
  readonly gaps: readonly KnownGap[];
  /** `"<jira>: <reason>"` when `key` is a gap of `target`, `undefined` otherwise. Exact match on the key. */
  lookup(key: string, target: RepsyTarget): string | undefined;
}

/** The text `test.fail` shows for a gap: the ticket first, so a report names it. */
export function gapReason(gap: Pick<KnownGap, 'jira' | 'reason'>): string {
  return `${gap.jira}: ${gap.reason}`;
}

/**
 * Validates `gaps` and returns them with their lookup. Throws, at load, on a key that is not a Jira key,
 * an empty reason, no target, or the same key listed twice for one target (the second would be dead).
 */
export function defineKnownGaps(gaps: readonly KnownGap[]): KnownGapRegistry {
  const seen = new Set<string>();
  for (const gap of gaps) {
    if (!JIRA_KEY.test(gap.jira)) {
      throw new Error(
        `known gap "${gap.key}": jira must be a key like RPS-1234, got "${gap.jira}"`,
      );
    }
    if (gap.reason.trim() === '') {
      throw new Error(`known gap "${gap.key}" (${gap.jira}) has no reason`);
    }
    if (gap.targets.length === 0) {
      throw new Error(`known gap "${gap.key}" (${gap.jira}) names no target`);
    }
    for (const target of gap.targets) {
      const id = `${target} ${gap.key}`;
      if (seen.has(id)) {
        throw new Error(`known gap "${gap.key}" is listed twice for ${target}`);
      }
      seen.add(id);
    }
  }
  return {
    gaps,
    lookup(key, target) {
      const gap = gaps.find((entry) => entry.key === key && entry.targets.includes(target));
      return gap ? gapReason(gap) : undefined;
    },
  };
}

/**
 * The key of a running test: `<project> > <file stem> > <describe titles and title, joined by " > ">`, for
 * example `skeleton > repo-settings > allowOverride is omitted from the settings ... (RPS-1435, CARGO)`.
 * A parameterised test carries its parameter in the title, so each is a key of its own.
 */
export function testGapKey(info: Pick<TestInfo, 'project' | 'file' | 'titlePath'>): string {
  const stem = (info.file.split(/[\\/]/).pop() ?? info.file).replace(
    /(\.(spec|test))?\.[cm]?[jt]sx?$/,
    '',
  );
  // `titlePath` starts with the file, then the describe titles, then the test title.
  return [info.project.name, stem, ...info.titlePath.slice(1)].join(' > ');
}

/** The `@cloud-skip` tag: what does not apply to Repsy Cloud at all (README "Tags"). */
export const CLOUD_SKIP_TAG = '@cloud-skip';

/** Whether a test carrying `tags` must not run on `targetName`: tagged `@cloud-skip` on a Repsy Cloud target. */
export function isCloudSkipped(
  tags: readonly string[],
  targetName: RepsyTarget = env.target,
): boolean {
  return capabilitiesFor(targetName).kind === 'cloud' && tags.includes(CLOUD_SKIP_TAG);
}

/**
 * Marks the running test as expected to fail while `targets` includes the run's target (`current`, the
 * `REPSY_TARGET` by default), and returns whether it did. The pin written in the spec itself, for a spec on
 * the bare `@playwright/test` `test`. Call it first in the test or in the `describe` body; the reason
 * names the ticket (`jira`).
 */
export function knownGapOn(
  targets: RepsyTarget | readonly RepsyTarget[],
  jira: string,
  reason: string,
  current: RepsyTarget = env.target,
): boolean {
  if (!JIRA_KEY.test(jira)) {
    throw new Error(`knownGapOn: jira must be a key like RPS-1234, got "${jira}"`);
  }
  const list: readonly RepsyTarget[] = typeof targets === 'string' ? [targets] : targets;
  if (!list.includes(current)) {
    return false;
  }
  test.fail(true, gapReason({ jira, reason }));
  return true;
}

/**
 * The value of an expectation that differs per target, for a spec outside the catalog (the catalog has
 * `expectByTarget`, `scenarios/types.ts`). The most specific entry wins: the exact target
 * (`cloud-local`), then its product (`cloud`, `os`), then `default`:
 *
 *   expect(status).toBe(byTarget({ default: 400, cloud: 404, 'cloud-local': 400 }));
 *
 * Read when called (never at import), so a `describe` body may not use it before the run's target is
 * known; call it inside the test.
 */
export function byTarget<T>(
  values: { default: T } & Partial<Record<RepsyTarget | 'os' | 'cloud', T>>,
  current: RepsyTarget = env.target,
): T {
  const exact = values[current];
  if (exact !== undefined) {
    return exact;
  }
  const byKind = values[capabilitiesFor(current).kind];
  return byKind !== undefined ? byKind : values.default;
}
