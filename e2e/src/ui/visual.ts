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
 * The one way a visual regression spec compares a page with its baseline (RPS-1652,
 * `tests/ui/visual/`, README.md "UI suite: visual regression").
 *
 * What makes a comparison stable, all of it here so a spec cannot forget one:
 *  - the web fonts are loaded (`document.fonts`), so a fallback font is never captured;
 *  - focus and the text caret are gone (a focused input draws a ring, a caret blinks);
 *  - everything that differs from one run to the next is a MASK, painted a flat colour over the element:
 *    the run id in a name, a timestamp, a size, a digest, the URL with the stack's port. A mask hides the
 *    text but not the box, so a change in the layout around it is still a failure;
 *  - animations are frozen (`animations: 'disabled'` in playwright.config.ts on top of the no-motion CSS of
 *    `defaults.ts`), the viewport, the scale factor, the colour scheme, the locale and the time zone are the
 *    project's (`ui-visual`), and only that one runner image renders (fonts, rasteriser).
 */
import { expect, type Locator, type Page } from '@playwright/test';

/** What a masked text is replaced with before the comparison: one text, so one box width (`expectVisual`). */
const MASKED_TEXT = 'MMMMMMMMMMMM';

/** The `data-testid`s whose text is different in every run: names carry the run id, dates and sizes move. */
export const DYNAMIC_ROW_IDS = [
  'row-name',
  'row-created',
  'row-updated',
  'row-published',
  'row-size',
  'row-digest',
  'row-description',
  // The links of a package list that carry a name: a scope is `@e2e-<run id>`.
  'row-scope-link',
  'row-group-link',
] as const;

/** Every element of `page` with one of `ids` as its `data-testid` (a row of a list, desktop or mobile). */
export function byTestIds(page: Page, ids: readonly string[]): Locator {
  return page.locator(ids.map((id) => `[data-testid="${id}"]`).join(', '));
}

/** The dynamic cells of the rows of any list (`DYNAMIC_ROW_IDS`). */
export function dynamicRowCells(page: Page): Locator {
  return byTestIds(page, DYNAMIC_ROW_IDS);
}

/**
 * Compares the viewport of `page` with `tests/ui/__screenshots__/<name>.png`. `masks` are painted over.
 * `--update-snapshots` (through `run.sh test --protocol ui-visual --update-snapshots`) writes the baseline.
 */
export async function expectVisual(
  page: Page,
  name: string,
  masks: readonly Locator[] = [],
): Promise<void> {
  await page.evaluate(async () => {
    await document.fonts.ready;
    (document.activeElement as HTMLElement | null)?.blur();
  });
  // A mask covers the BOX of its element, and the box of a text is as wide as the text: names with a
  // different run id are a few pixels apart in a proportional font, and so would be the masks. Give every
  // masked text the same text first, so the box is the same in every run.
  for (const mask of masks) {
    await mask.evaluateAll((elements, text) => {
      for (const element of elements) {
        // Every text node below the element (a tooltip component wraps its text in a span or two), the
        // structure around it untouched.
        const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          if (node.nodeValue?.trim()) {
            node.nodeValue = text;
          }
        }
      }
    }, MASKED_TEXT);
  }
  await expect(page).toHaveScreenshot(`${name}.png`, { mask: [...masks], maskColor: '#ff00ff' });
}
