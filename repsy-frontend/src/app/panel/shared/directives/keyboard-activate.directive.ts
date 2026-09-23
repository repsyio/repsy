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

import { Directive, ElementRef, HostListener, inject } from '@angular/core';

/**
 * Makes a non-native clickable element (for example a table row or card with a `routerLink` or
 * `(click)` handler) operable from the keyboard: it joins the tab order, defaults to
 * `role="button"` and turns Enter or Space into a click, like a native button does.
 *
 * Only keys pressed on the element itself are handled, so links and buttons nested inside the row
 * keep their own native keyboard behaviour.
 */
@Directive({
  selector: '[appKeyboardActivate]',
  standalone: true,
  host: {
    role: 'button',
    tabindex: '0',
  },
})
export class KeyboardActivateDirective {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);

  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (event.target !== this.element.nativeElement || event.defaultPrevented) {
      return;
    }
    if (event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar') {
      event.preventDefault();
      this.element.nativeElement.click();
    }
  }
}
