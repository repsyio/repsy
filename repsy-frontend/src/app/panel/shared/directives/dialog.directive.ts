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

import { afterNextRender, DestroyRef, Directive, ElementRef, HostListener, inject, input, output } from '@angular/core';

import { uniqueId } from '../../../shared/util/unique-id';

const TABBABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), ' +
  'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** The open dialogs, oldest first: only the last one (the topmost) reacts to the keyboard. */
const openDialogs: DialogDirective[] = [];

/**
 * The dialog semantics of a modal, in one place (RPS-1266): put `appDialog` on the element that holds
 * the modal's content (not on its backdrop).
 *
 * - `role="dialog"` (`appDialog="alertdialog"` for a confirmation), `aria-modal="true"` and
 *   `aria-labelledby`: give the title element `[id]="dialog.titleId"` (`#dialog="appDialog"`).
 * - Focus moves into the dialog when it appears: to the element marked `data-dialog-focus`, else to
 *   the dialog itself. It returns to the element that had focus (the opener) when the dialog goes.
 * - Tab and Shift+Tab cycle inside the topmost dialog. A dialog opened from another one (a security
 *   modal from a security modal) takes over the keyboard until it closes.
 * - Escape emits `appDialogClose`, unless `appDialogClosable` is false (a request is in flight) or
 *   something inside already handled the key (`preventDefault`, for example an open menu).
 *
 * The directive lives and dies with the element: modals show their content in an `@if (open)`.
 */
@Directive({
  selector: '[appDialog]',
  exportAs: 'appDialog',
  host: {
    '[attr.role]': 'role()',
    'aria-modal': 'true',
    '[attr.aria-labelledby]': 'titleId',
    '[attr.tabindex]': '-1',
    '[style.outline]': '"none"',
  },
})
export class DialogDirective {
  /** `''` or `dialog` for a dialog, `alertdialog` for a confirmation. */
  public readonly appDialog = input<string>('');
  public readonly appDialogClosable = input<boolean>(true);
  public readonly appDialogClose = output<void>();

  /** The id of the element that names the dialog (its title). */
  public readonly titleId = uniqueId('dialog-title');

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;

  constructor() {
    openDialogs.push(this);
    afterNextRender(() => this.moveFocusIn());
    inject(DestroyRef).onDestroy(() => {
      openDialogs.splice(openDialogs.indexOf(this), 1);
      this.returnFocus();
    });
  }

  public role(): string {
    return this.appDialog() === 'alertdialog' ? 'alertdialog' : 'dialog';
  }

  @HostListener('document:keydown', ['$event'])
  public onKeydown(event: KeyboardEvent): void {
    if (openDialogs[openDialogs.length - 1] !== this || event.defaultPrevented) {
      return;
    }
    if (event.key === 'Escape') {
      if (this.appDialogClosable()) {
        event.preventDefault();
        this.appDialogClose.emit();
      }
    } else if (event.key === 'Tab') {
      this.trapTab(event);
    }
  }

  private moveFocusIn(): void {
    const target = this.host.querySelector<HTMLElement>('[data-dialog-focus]');
    (target ?? this.host).focus();
  }

  private returnFocus(): void {
    // Not when the opener is gone (a row menu item that vanished).
    if (this.opener?.isConnected && this.opener !== document.body) {
      this.opener.focus();
    }
  }

  private trapTab(event: KeyboardEvent): void {
    const items = this.tabbable();
    if (items.length === 0) {
      event.preventDefault();
      this.host.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    const inside = this.host.contains(active);
    if (event.shiftKey && (!inside || active === first || active === this.host)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (!inside || active === last)) {
      event.preventDefault();
      first.focus();
    }
  }

  private tabbable(): HTMLElement[] {
    return Array.from(this.host.querySelectorAll<HTMLElement>(TABBABLE)).filter(
      (element) => element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden',
    );
  }
}
