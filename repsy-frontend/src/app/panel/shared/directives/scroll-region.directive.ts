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

import { afterNextRender, DestroyRef, Directive, ElementRef, inject, input, signal } from '@angular/core';

/**
 * Marks a code block as a labelled, keyboard-focusable scroll region, but only while it scrolls.
 *
 * A scrollable element has to be reachable by keyboard (axe `scrollable-region-focusable`, WCAG 2.1.1),
 * yet a block that fits makes a pointless tab stop. The host therefore gets `role="region"`,
 * `aria-label` and `tabindex="0"` while its content overflows horizontally or vertically, and none
 * of them otherwise. The overflow is re-measured when the host is resized or its content changes
 * (the highlighter fills the block after the first render).
 *
 * Put it on the element that actually scrolls: for the highlighted snippets that is the `<code>`
 * element (the highlight.js theme gives it `overflow-x: auto`, and as a flex child of the `<pre>` it
 * is also clamped to the `<pre>`'s max height), not the `<pre>`.
 */
@Directive({
  selector: '[appScrollRegion]',
  standalone: true,
  host: {
    '[attr.role]': 'scrollable() ? "region" : null',
    '[attr.aria-label]': 'scrollable() ? label() : null',
    '[attr.tabindex]': 'scrollable() ? 0 : null',
  },
})
export class ScrollRegionDirective {
  /** The accessible name of the region, for example "Maven dependency". */
  readonly label = input.required<string>({ alias: 'appScrollRegion' });

  protected readonly scrollable = signal(false);

  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const measure = (): void => {
        const { scrollWidth, clientWidth, scrollHeight, clientHeight } = this.element;
        this.scrollable.set(scrollWidth > clientWidth || scrollHeight > clientHeight);
      };
      const resizeObserver = new ResizeObserver(measure);
      const mutationObserver = new MutationObserver(measure);
      resizeObserver.observe(this.element);
      mutationObserver.observe(this.element, { childList: true, subtree: true, characterData: true });
      destroyRef.onDestroy(() => {
        resizeObserver.disconnect();
        mutationObserver.disconnect();
      });
      measure();
    });
  }
}
