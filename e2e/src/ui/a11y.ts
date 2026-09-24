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
 * The axe-core accessibility helper of the UI suite (RPS-1258, A11Y-01).
 *
 * Two modes, controlled in ONE place:
 *
 *  - `enforce` (the default since RPS-1266 part 4): every scan attaches its findings to the test
 *    report (`axe-<label>.json` plus a one-line `axe-<label>.txt` and a test annotation), prints a
 *    compact summary line, and the test fails when a violation of a blocking impact (`serious` or
 *    `critical`) is present.
 *  - `report`: the same, and NEVER fails. This is how the baseline was recorded while the panel still
 *    had accessibility debt, and how to look at a page that is not clean yet.
 *
 * Change the default for everyone with `DEFAULT_A11Y_MODE` below (that is the "one config flag" of
 * the story), or for a single run with `REPSY_UI_OPT_IN=a11y-report` (or `a11y-enforce`), which
 * `docker-compose.runners.yml` already forwards to the ui runner. A dedicated `REPSY_UI_A11Y`
 * variable would not reach the container without editing that file.
 *
 * Note on the harness: the Font Awesome CDN is blocked (`defaults.ts`), so its icon buttons render as
 * empty, zero-size boxes here. Those buttons have no accessible name in production either (an
 * `<i class="fa ...">` carries no text), but a rule that is about size or colour of a glyph could
 * differ from production, so every summary counts how many of a rule's nodes are Font Awesome icons
 * (`faNodes`) to keep them separable from the rest.
 */
import { writeFile } from 'node:fs/promises';

import AxeBuilder from '@axe-core/playwright';
import type { Page, TestInfo } from '@playwright/test';
import { expect } from '@playwright/test';

import { optedIn } from './session.js';

export type A11yMode = 'report' | 'enforce';

/**
 * THE switch. It was `report` while the baseline was being fixed (RPS-1266); part 4 made the five
 * scanned pages, the open modals and the package pages of every protocol free of serious/critical
 * violations and flipped it to `enforce`.
 */
export const DEFAULT_A11Y_MODE: A11yMode = 'enforce';

/** The axe impacts that fail a test in `enforce` mode. */
export const BLOCKING_IMPACTS: readonly string[] = ['serious', 'critical'];

/** The rule sets scanned: WCAG 2.0 and 2.1, levels A and AA (axe's `best-practice` rules are not included). */
const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

export function a11yMode(): A11yMode {
  if (optedIn('a11y-enforce')) {
    return 'enforce';
  }
  if (optedIn('a11y-report')) {
    return 'report';
  }
  return DEFAULT_A11Y_MODE;
}

export interface RuleSummary {
  id: string;
  impact: string;
  /** How many elements violate the rule. */
  nodes: number;
  /** How many of those are Font Awesome icons (`class="fa ..."`), see the file header. */
  faNodes: number;
  help: string;
}

export interface AxeSummary {
  label: string;
  url: string;
  mode: A11yMode;
  rules: RuleSummary[];
  /** Violated rules per impact (`critical`, `serious`, `moderate`, `minor`). */
  byImpact: Record<string, number>;
  /** The rules that fail the test in `enforce` mode (`BLOCKING_IMPACTS`). */
  blocking: RuleSummary[];
}

const FA_PATTERN = /class="[^"]*\bfa[a-z]?\b/;

/** The compact one-line form: `login: 3 rules (serious 2, moderate 1): button-name(serious,4) ...`. */
export function formatSummary(summary: AxeSummary): string {
  const impacts = Object.entries(summary.byImpact)
    .map(([impact, count]) => `${impact} ${count}`)
    .join(', ');
  const rules = summary.rules.map((rule) => `${rule.id}(${rule.impact},${rule.nodes})`).join(' ');
  return `AXE ${summary.label} [${summary.mode}]: ${summary.rules.length} rules (${impacts || 'none'}) ${rules}`.trim();
}

/**
 * Runs axe on the page as it is NOW (the caller has already waited for the page's own ready element),
 * attaches the findings to the report and, in `enforce` mode (the default), fails on serious/critical
 * violations.
 * Returns the summary so a spec can collect a table.
 */
export async function scanPage(
  page: Page,
  testInfo: TestInfo,
  label: string,
  /** A CSS selector to scan only that subtree (an open modal), instead of the whole page. */
  include?: string,
): Promise<AxeSummary> {
  const builder = new AxeBuilder({ page }).withTags(AXE_TAGS);
  const results = await (include ? builder.include(include) : builder).analyze();

  const rules: RuleSummary[] = results.violations.map((violation) => ({
    id: violation.id,
    impact: violation.impact ?? 'unknown',
    nodes: violation.nodes.length,
    faNodes: violation.nodes.filter((node) => FA_PATTERN.test(node.html)).length,
    help: violation.help,
  }));
  const byImpact: Record<string, number> = {};
  for (const rule of rules) {
    byImpact[rule.impact] = (byImpact[rule.impact] ?? 0) + 1;
  }
  const mode = a11yMode();
  const summary: AxeSummary = {
    label,
    url: results.url,
    mode,
    rules,
    byImpact,
    blocking: rules.filter((rule) => BLOCKING_IMPACTS.includes(rule.impact)),
  };

  const line = formatSummary(summary);
  console.log(line);
  testInfo.annotations.push({ type: 'axe', description: line });
  // On disk too (`test-results/<test>/axe-<label>.json`), so the full findings can be read without
  // opening the HTML report.
  const jsonPath = testInfo.outputPath(`axe-${label}.json`);
  await writeFile(jsonPath, JSON.stringify({ summary, violations: results.violations }, null, 2));
  await testInfo.attach(`axe-${label}.json`, { path: jsonPath, contentType: 'application/json' });
  await testInfo.attach(`axe-${label}.txt`, { body: line, contentType: 'text/plain' });

  if (mode === 'enforce') {
    expect(
      summary.blocking.map(
        (rule) => `${rule.id} (${rule.impact}, ${rule.nodes} nodes): ${rule.help}`,
      ),
      `${label}: serious/critical axe violations (REPSY_UI_OPT_IN=a11y-report to only report)`,
    ).toEqual([]);
  }
  return summary;
}
