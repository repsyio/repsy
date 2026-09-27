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
 * Layout checks for a page at a phone's width (RPS-1650, NAV-10..): does the content fit, is a control
 * reachable, does a modal fit.
 *
 * `document.documentElement.scrollWidth <= clientWidth` alone proves little on this panel: the layout's
 * content column (`panel-content`) is `overflow: hidden`, so a wide table or a long unbroken string is
 * CLIPPED there instead of scrolling the page, and the page-level check passes while the content is
 * unreachable. `horizontalOverflow` therefore measures the page AND that column, and names the elements
 * that stick out past the viewport with no scroller of their own to reach them.
 */
import { expect, type Locator, type Page } from '@playwright/test';

export interface HorizontalOverflow {
  /** `documentElement.scrollWidth - clientWidth`: what a horizontal page scrollbar would show. */
  page: number;
  /** The same for the layout's content column, which clips instead of scrolling. */
  content: number;
  /** Elements whose right edge is past the viewport and that no ancestor scrolls or clips on purpose. */
  offenders: string[];
}

/** Measures the page as it is now (a fixed header, an open modal and the toast stack included). */
export function horizontalOverflow(page: Page): Promise<HorizontalOverflow> {
  return page.evaluate(() => {
    const width = document.documentElement.clientWidth;
    const column = document.querySelector('[data-testid="panel-content"]');
    const scrolls = (element: Element): boolean => {
      const overflowX = getComputedStyle(element).overflowX;
      return overflowX === 'auto' || overflowX === 'scroll' || overflowX === 'hidden';
    };
    const offenders: string[] = [];
    for (const element of Array.from(document.body.querySelectorAll('*'))) {
      const rect = element.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0 || rect.right <= width + 1) {
        continue;
      }
      if (getComputedStyle(element).position === 'fixed' && rect.left >= width) {
        continue; // parked off screen (a closed drawer)
      }
      // Fine when an ancestor (or the element's own box) deliberately scrolls or clips it.
      let ancestor: Element | null = element.parentElement;
      let contained = false;
      // The content column itself clips by accident, not on purpose: it does not count.
      while (ancestor && ancestor !== document.body && ancestor !== column) {
        if (scrolls(ancestor) && ancestor.getBoundingClientRect().right <= width + 1) {
          contained = true;
          break;
        }
        ancestor = ancestor.parentElement;
      }
      if (contained) {
        continue;
      }
      const testId = element.getAttribute('data-testid');
      offenders.push(
        `${element.tagName.toLowerCase()}${testId ? `[data-testid=${testId}]` : ''} right=${Math.round(rect.right)} (viewport ${width})`,
      );
    }
    return {
      page: document.documentElement.scrollWidth - width,
      content: column ? column.scrollWidth - column.clientWidth : 0,
      offenders: offenders.slice(0, 8),
    };
  });
}

/** Fails, naming what sticks out, unless the page and its content column fit the viewport's width. */
export async function expectNoHorizontalOverflow(page: Page, label: string): Promise<void> {
  const overflow = await horizontalOverflow(page);
  expect(overflow, `${label}: horizontal overflow at ${page.viewportSize()?.width}px`).toEqual({
    page: 0,
    content: 0,
    offenders: [],
  });
}

/**
 * The control can be used by a person: scrolled to the middle of the screen it is inside the viewport's
 * width and the topmost element at its centre is the control itself (or its label or a child), so nothing
 * (the fixed header, a toast, a sibling) covers it. Works for a disabled control too, which a Playwright
 * trial click refuses. The header shrinks once the page is scrolled, so the check waits two frames after
 * the scroll before it measures.
 */
export async function expectReachable(page: Page, control: Locator, label: string): Promise<void> {
  await control.scrollIntoViewIfNeeded();
  await expect(control, `${label} is visible`).toBeVisible();
  const problem = await control.evaluate(async (element) => {
    element.scrollIntoView({ block: 'center', inline: 'nearest' });
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
    const box = element.getBoundingClientRect();
    if (box.left < -1 || box.right > window.innerWidth + 1) {
      return `sticks out of the viewport (x ${Math.round(box.left)}..${Math.round(box.right)} of ${window.innerWidth})`;
    }
    const x = box.x + box.width / 2;
    const y = box.y + box.height / 2;
    if (y < 0 || y > window.innerHeight) {
      return `is outside the viewport (y=${Math.round(y)})`;
    }
    const hit = document.elementFromPoint(x, y);
    if (hit && (element.contains(hit) || hit.contains(element))) {
      return null;
    }
    const testId = hit?.getAttribute('data-testid');
    return `is covered by ${hit ? hit.tagName.toLowerCase() : 'nothing'}${testId ? `[data-testid=${testId}]` : ''}`;
  });
  expect(problem, `${label} is reachable`).toBeNull();
}

/**
 * An open modal fits the phone: within the viewport's width, and either within its height or scrollable
 * inside (never a fixed panel taller than the screen that cannot be scrolled), with `controls` (its
 * buttons) reachable.
 */
export async function expectModalFits(
  page: Page,
  dialog: Locator,
  label: string,
  controls: Record<string, Locator> = {},
): Promise<void> {
  const viewport = page.viewportSize();
  const box = await dialog.boundingBox();
  expect(box, `${label} has a box`).not.toBeNull();
  expect(box?.x, `${label} starts inside the viewport`).toBeGreaterThanOrEqual(0);
  expect(
    (box?.x ?? 0) + (box?.width ?? 0),
    `${label} ends inside the viewport`,
  ).toBeLessThanOrEqual((viewport?.width ?? 0) + 1);
  const scrollable = await dialog.evaluate((element) => {
    // The dialog itself, or a descendant that scrolls, can reach content below the fold.
    return [element, ...Array.from(element.querySelectorAll('*'))].some((candidate) => {
      const style = getComputedStyle(candidate);
      return (
        (style.overflowY === 'auto' || style.overflowY === 'scroll') &&
        candidate.scrollHeight > candidate.clientHeight
      );
    });
  });
  const fits = (box?.y ?? 0) >= 0 && (box?.y ?? 0) + (box?.height ?? 0) <= (viewport?.height ?? 0);
  expect(
    fits || scrollable,
    `${label} fits the height (${JSON.stringify(box)}) or scrolls inside`,
  ).toBe(true);
  for (const [name, control] of Object.entries(controls)) {
    await expectReachable(page, control, `${label}: ${name}`);
  }
}

/**
 * A description of the focused element when something else covers its centre point (the fixed header
 * after a Tab scrolled it under, a toast, an overlay), or null when the focus is visible. WCAG 2.2's
 * "Focus Not Obscured": a keyboard user must be able to see where they are.
 */
export function obscuredFocus(page: Page): Promise<string | null> {
  return page.evaluate(() => {
    const focused = document.activeElement;
    if (!focused || focused === document.body || focused === document.documentElement) {
      return null;
    }
    const box = focused.getBoundingClientRect();
    if (box.width === 0 || box.height === 0) {
      return null;
    }
    const x = Math.min(Math.max(box.x + box.width / 2, 0), window.innerWidth - 1);
    const y = box.y + box.height / 2;
    const describe = (element: Element): string => {
      const testId = element.getAttribute('data-testid');
      return `${element.tagName.toLowerCase()}${testId ? `[data-testid=${testId}]` : ''}`;
    };
    if (y < 0 || y > window.innerHeight) {
      return `${describe(focused)} is outside the viewport (y=${Math.round(y)})`;
    }
    const hit = document.elementFromPoint(x, y);
    // A label, a wrapper or a child of the control is the control being hit.
    if (hit && (focused.contains(hit) || hit.contains(focused))) {
      return null;
    }
    return `${describe(focused)} at y=${Math.round(y)} is covered by ${hit ? describe(hit) : 'nothing'}`;
  });
}
