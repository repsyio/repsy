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

import { afterNextRender, DestroyRef, Directive, ElementRef, inject } from '@angular/core';

/**
 * Moves its host element to the end of `document.body`, out of whatever element declares it.
 *
 * A dialog that is declared inside a clickable list row (the security badge of a row hosts its
 * modal) would otherwise (1) hand every click and key press made inside it to the row, which then
 * navigates away, and (2) be painted and positioned within the row's stacking context and
 * transformed ancestors, so the page header can end up above it. At the end of the body it is a
 * plain top-level overlay. The host is removed again when the directive is destroyed.
 *
 * Angular keeps its own references to the node, so bindings, inputs, outputs and change detection
 * of the moved component are unaffected.
 */
@Directive({
  selector: '[appPortalToBody]',
  standalone: true,
})
export class PortalToBodyDirective {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  constructor() {
    // After the render that inserts the host into its declaring view: an embedded view (an `@if`
    // block) attaches its root nodes only then, which would undo an earlier move.
    afterNextRender(() => document.body.appendChild(this.element));
    inject(DestroyRef).onDestroy(() => this.element.remove());
  }
}
