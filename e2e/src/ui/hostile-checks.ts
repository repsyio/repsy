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
 * The assertions of the hostile-content specs (RPS-1623), next to the payloads of `hostile-packages.ts`
 * so that Repsy Cloud's suite (RPS-1624) reuses both.
 *
 * A link is judged by what its `href` is allowed to BE (an allow-list: http(s), mailto:, tel:, an app path,
 * or the inert `unsafe:` prefix), never by looking for a bad scheme: a deny-list misses the next scheme,
 * and a `startsWith('javascript:')` check is what CodeQL flags in a spec.
 */
import { expect, type Locator, type Page } from '@playwright/test';

import { CANARY, HOSTILE_URLS, TRACKER_HOST } from './hostile-packages.js';

/** Elements a README must never contain (they fetch, embed or run something). */
const FORBIDDEN_ELEMENTS = 'script, iframe, object, embed, link, form, style, svg[onload]';
/** Event handlers, on any element (a panel page has none of its own in the DOM). */
const HANDLER_ATTRIBUTES = '[onerror], [onclick], [onload], [onmouseover]';
/** A README must also lose inline style (an external background) and srcset (a hidden request). */
const README_ATTRIBUTES = `${HANDLER_ATTRIBUTES}, [style], [srcset]`;

/** What an `href` on the page may be: the panel's own routes, http(s), mailto:, tel:, or the inert `unsafe:` form. */
const ALLOWED_HREF = /^(https?:\/\/|mailto:|tel:|unsafe:|\/|#)/i;

/** The requests a page makes to `host`, recorded from now on (the harness aborts them, but `request` still fires). */
export function watchRequestsTo(page: Page, host: string = TRACKER_HOST): { urls: string[] } {
  const recorded = { urls: [] as string[] };
  page.on('request', (request) => {
    if (new URL(request.url()).hostname === host) {
      recorded.urls.push(request.url());
    }
  });
  return recorded;
}

/** The value of the canary, `undefined` while no payload ran. */
export async function canary(page: Page): Promise<unknown> {
  return page.evaluate((name) => (window as unknown as Record<string, unknown>)[name], CANARY);
}

/**
 * Nothing ran: the canary is unset after the page settled (an `onerror`/`onload` handler fires on the load
 * of its element, so the page is given its network idle first) and nothing asked for the tracker.
 */
export async function expectNothingRan(page: Page, requests: { urls: string[] }): Promise<void> {
  await page.waitForLoadState('networkidle');
  expect(await canary(page), 'a payload ran and set window.__pwned').toBeUndefined();
  expect(requests.urls, 'the page requested a publisher-chosen host').toEqual([]);
}

/** Every anchor under `scope` has an href of an allowed kind, and an external one opens safely. */
export async function expectSafeLinks(scope: Locator): Promise<void> {
  const anchors = await scope.locator('a[href]').evaluateAll((elements) =>
    elements.map((element) => ({
      href: element.getAttribute('href') ?? '',
      target: element.getAttribute('target'),
      rel: element.getAttribute('rel') ?? '',
    })),
  );
  for (const anchor of anchors) {
    expect(anchor.href, `an anchor has a live href: ${anchor.href}`).toMatch(ALLOWED_HREF);
    if (/^https?:\/\//i.test(anchor.href)) {
      expect(anchor.target, `${anchor.href} must open in a new tab`).toBe('_blank');
      expect(anchor.rel, `${anchor.href} must not leak the panel`).toMatch(/noopener/);
      expect(anchor.rel, `${anchor.href} must not send a referrer`).toMatch(/noreferrer/);
    }
  }
}

/**
 * The README of a package published with `HOSTILE_README` rendered inert: no dangerous element or
 * attribute, no picture (every external or non-image one became its alt text), no publisher comment, and every link
 * either kept safely, rewritten to `unsafe:` (a script scheme) or turned into text (a relative one).
 */
export async function expectInertReadme(readme: Locator): Promise<void> {
  await expect(readme.getByRole('heading', { name: 'Hostile README', level: 1 })).toBeVisible();

  await expect(readme.locator(FORBIDDEN_ELEMENTS)).toHaveCount(0);
  await expect(readme.locator(README_ATTRIBUTES)).toHaveCount(0);
  // Angular leaves its own anchor comments (`<!--container-->`) in a page; the publisher's must be gone.
  const comments = await readme.evaluate((element) => {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_COMMENT);
    const found: string[] = [];
    while (walker.nextNode()) {
      found.push(walker.currentNode.nodeValue ?? '');
    }
    return found;
  });
  expect(
    comments.filter((text) => /script|pwned/i.test(text)),
    'a publisher comment survived',
  ).toEqual([]);

  // Pictures: none load. The external and the data-document one leave their alt text (and a plain link for
  // the external), the relative one only its alt text.
  await expect(readme.locator('img')).toHaveCount(0);
  const blocked = readme.locator('.blocked-image');
  await expect(blocked.filter({ hasText: 'tracking pixel' })).toHaveCount(1);
  await expect(blocked.filter({ hasText: 'data document' })).toHaveCount(1);
  await expect(blocked.filter({ hasText: 'relative picture' })).toHaveCount(1);

  // Script schemes: the link stays visible but its href is the inert `unsafe:` form.
  const link = (name: string) => readme.getByRole('link', { name, exact: true });
  await expect(link('javascript link')).toHaveAttribute('href', /^unsafe:javascript:/);
  await expect(link('obfuscated link')).toHaveAttribute('href', /^unsafe:/);
  await expect(link('data link')).toHaveAttribute(
    'href',
    new RegExp(`^unsafe:${escapeRegExp(HOSTILE_URLS.data)}$`),
  );
  await expect(link('vbscript link')).toHaveAttribute('href', /^unsafe:vbscript:/);

  // A relative link resolves against the panel: it is text, not a link.
  await expect(link('relative link')).toHaveCount(0);
  await expect(readme).toContainText('relative link');

  // The controls survive, and the external one opens safely.
  await expect(link('external link')).toHaveAttribute('href', 'https://example.com/docs');
  await expect(link('mail the author')).toHaveAttribute('href', 'mailto:author@example.com');
  await expect(link('handler link')).toHaveAttribute('href', 'https://example.com/handler');

  await expectSafeLinks(readme);
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * A page section that shows hostile publisher TEXT (a description, a homepage, a project URL) without
 * running or linking it: nothing dangerous in the DOM, and every link on it an allowed kind.
 */
export async function expectInertSection(section: Locator): Promise<void> {
  await expect(section.locator(FORBIDDEN_ELEMENTS)).toHaveCount(0);
  await expect(section.locator(HANDLER_ATTRIBUTES)).toHaveCount(0);
  await expect(section.locator('img[src="x"]')).toHaveCount(0);
  await expectSafeLinks(section);
}
