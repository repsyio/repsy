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
 * Where to click a list row so that the row's own link receives the click (RPS-1337).
 *
 * The row link is stretched over the whole row, and the row's other controls (the security badge, the
 * action menu, a secondary link) sit above it on purpose: a click on one of them does what that
 * control does. With the scanner on, a Maven versions or artifacts row has its security badge cell in
 * the middle of the row, so the exact centre of the row IS the badge and a click there opens the
 * security modal, not the row. A test that means "open the row" therefore clicks the point nearest to
 * the centre that no control covers: the centre itself when it is free (every row without a badge,
 * so nothing changes on the default stack), otherwise the closest free point on the row's middle line.
 */
import type { Locator } from '@playwright/test';

/** A point in a row, relative to its top left corner. */
export interface RowPoint {
  x: number;
  y: number;
}

/** What a click on the row must not land on: anything that is a control of its own. */
const CONTROL = 'button, a:not(.row-link), input, select, textarea, [role="button"], app-dropdown';

/**
 * The point of `row` nearest to its centre, on its middle line, where the topmost element is the
 * row's stretched link or plain row content (a tooltip text hands its click to the link), not a
 * control. Throws when the whole middle line is covered by controls.
 */
export async function rowLinkPoint(row: Locator): Promise<RowPoint> {
  await row.scrollIntoViewIfNeeded();
  const point = await row.evaluate((element, control) => {
    const box = element.getBoundingClientRect();
    const y = box.height / 2;
    // 0, then +1, -1, +2, -2 ... percent of the width away from the centre.
    for (let step = 0; step <= 100; step += 1) {
      for (const offset of step === 0 ? [0] : [step, -step]) {
        const x = box.width / 2 + (box.width * offset) / 100;
        if (x < 1 || x > box.width - 1) {
          continue;
        }
        const hit = document.elementFromPoint(box.x + x, box.y + y);
        if (hit !== null && element.contains(hit) && hit.closest(control) === null) {
          return { x, y };
        }
      }
    }
    return null;
  }, CONTROL);
  if (point === null) {
    throw new Error('no point of the row is free of controls: the row link cannot be clicked');
  }
  return point;
}

/** The element the pointer would hit at the centre of `row`: a short description, for failures. */
export async function describeCentre(row: Locator): Promise<string> {
  return row.evaluate((element) => {
    const box = element.getBoundingClientRect();
    const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
    return hit === null
      ? '(nothing)'
      : `${hit.tagName.toLowerCase()}[data-testid=${hit.getAttribute('data-testid')}]`;
  });
}
