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
 * A list keeps its rows and pager mounted while it reloads (RPS-1669: it used to swap the whole thing
 * for a spinner, destroying whatever had the keyboard focus). That fixes a page change, where the
 * clicked control survives in place. A row delete is different: the control that had the focus (a row's
 * menu button) is really gone once the row is, and nothing else claims the focus, so the browser drops
 * it on `<body>`.
 *
 * `restoreListFocus` is the fallback for that case. Call it once a reload's new rows are in and the view
 * has had a tick to render them, with the element that had the focus before the reload started (or
 * `null`/`document.body` if nothing meaningful did) and the list's own root element.
 */
export function restoreListFocus(previouslyFocused: Element | null, container: HTMLElement | null | undefined): void {
  if (!previouslyFocused || previouslyFocused === document.body || !container) {
    // Nothing was purposefully focused before the reload (a mouse-driven flow, or the first, cold
    // load): moving the focus now would steal it from wherever the user put it since.
    return;
  }

  // Angular has not necessarily painted the new rows yet: give it one tick before looking.
  setTimeout(() => {
    if (document.activeElement !== document.body) {
      return; // The focus survived (the same control), or something else already claimed it.
    }
    focusFirstOf(container, CANDIDATES);
  });
}

/** In priority order: a row's own menu, the pager, then anything else still tabbable in the list. */
const CANDIDATES = [
  '[data-testid="row-menu"] [data-testid="dropdown-toggle"]',
  '[data-testid="pagination"] button:not([aria-disabled="true"])',
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), [tabindex]:not([tabindex="-1"])',
];

function focusFirstOf(container: HTMLElement, selectors: string[]): void {
  for (const selector of selectors) {
    for (const candidate of Array.from(container.querySelectorAll<HTMLElement>(selector))) {
      // Skip a match hidden by the responsive layout (the desktop table's row menu while on a phone).
      if (candidate.getClientRects().length > 0) {
        candidate.focus();
        return;
      }
    }
  }
}
