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

import { booleanAttribute, Component, ElementRef, HostListener, inject, Input, ViewChild } from '@angular/core';

/**
 * A short text with the full value in a popup on hover.
 *
 * Two modes. By default the caller passes an already shortened `text` (usually through the `ellipsis`
 * pipe) and the full value in `textHover`; the popup opens when `text` is longer than `maxLength`.
 * With the `truncate` attribute the caller passes the FULL value as `text`: the host fills its grid
 * cell or flex slot, the browser clips the text with a CSS ellipsis at the cell width, and the popup
 * (`textHover`, or `text` when it is not set) opens only when the text really is clipped. The full
 * value always stays in the DOM, so similar long names remain distinguishable to assistive
 * technology and to tests.
 *
 * Inside a list row (`.row-link-host`) the tooltip sits above the row's stretched link so that its
 * hover keeps working; a click on its text is then handed to that link, so the whole row still opens
 * on a click. The popup itself never takes the pointer (`pointer-events-none`): it fades in under the
 * arriving pointer, and a popup that took the click there swallowed the click on the middle of the row
 * (RPS-1324).
 */
@Component({
  selector: 'app-tooltip',
  templateUrl: './tooltip.component.html',
  imports: [],
  standalone: true,
  host: {
    '[class.block]': 'truncate',
    '[class.min-w-0]': 'truncate',
  },
})
export class TooltipComponent {
  @Input() text: string;
  @Input() textHover: string;
  @Input() maxLength = 10;
  @Input() always = false;
  @Input({ transform: booleanAttribute }) truncate = false;

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  @ViewChild('label') private label?: ElementRef<HTMLElement>;

  isVisible = false;
  /** True while the text is wider than its slot; measured when the pointer enters. */
  isClipped = false;

  get showPopup(): boolean {
    if (!this.isVisible) {
      return false;
    }
    if (this.always) {
      return true;
    }
    return this.truncate ? this.isClipped : (this.text?.length ?? 0) > this.maxLength;
  }

  get popupText(): string {
    return this.truncate ? (this.textHover ?? this.text) : this.textHover;
  }

  @HostListener('mouseenter') onMouseEnter() {
    const label = this.label?.nativeElement;
    this.isClipped = !!label && label.scrollWidth > label.clientWidth;
    this.isVisible = true;
  }

  @HostListener('mouseleave') onMouseLeave() {
    this.isVisible = false;
  }

  /** Hands a click on the text to the stretched link of the row that hosts the tooltip, if any. */
  @HostListener('click', ['$event'])
  onClick(event: MouseEvent) {
    const element = this.host.nativeElement;
    if (event.defaultPrevented || element.closest('a, button')) {
      return;
    }
    const link = element.closest('.row-link-host')?.querySelector<HTMLAnchorElement>(':scope > .row-link');
    link?.dispatchEvent(
      new MouseEvent('click', {
        bubbles: true,
        cancelable: true,
        view: window,
        ctrlKey: event.ctrlKey,
        metaKey: event.metaKey,
        shiftKey: event.shiftKey,
        altKey: event.altKey,
      }),
    );
  }
}
