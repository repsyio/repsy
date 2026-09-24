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

import { ChangeDetectorRef, Component, ElementRef, HostListener, Input } from '@angular/core';

const FOCUSABLE_ITEMS = 'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

@Component({
  selector: 'app-dropdown',
  templateUrl: './dropdown.component.html',
  standalone: true,
  imports: [],
})
export class DropdownComponent {
  private static activeDropdown: DropdownComponent | null = null;
  private static nextId = 0;

  /** Accessible name of the trigger button and of the menu it opens. */
  @Input() public label = 'More options';

  public isOpen = false;
  public readonly menuId = `app-dropdown-menu-${DropdownComponent.nextId++}`;

  constructor(
    private eRef: ElementRef<HTMLElement>,
    private cdr: ChangeDetectorRef,
  ) {}

  toggleDropdown() {
    if (this.isOpen) {
      this.close();
      return;
    }
    if (DropdownComponent.activeDropdown && DropdownComponent.activeDropdown !== this) {
      DropdownComponent.activeDropdown.isOpen = false;
    }

    this.isOpen = true;
    DropdownComponent.activeDropdown = this;
    this.markMenuItems();
  }

  /** ArrowDown on the trigger opens the menu and moves focus to its first item. */
  onTriggerArrowDown(event: Event) {
    event.preventDefault();
    if (!this.isOpen) {
      this.toggleDropdown();
    }
    this.menuItems()[0]?.focus();
  }

  onMenuKeydown(event: KeyboardEvent) {
    const items = this.menuItems();
    if (items.length === 0) {
      return;
    }
    const current = items.indexOf(document.activeElement as HTMLElement);
    let next: number;
    switch (event.key) {
      case 'ArrowDown':
        next = (current + 1) % items.length;
        break;
      case 'ArrowUp':
        next = current <= 0 ? items.length - 1 : current - 1;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = items.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    items[next].focus();
  }

  /**
   * Choosing an action closes the menu (the action's own click handler has already run by now). When it
   * was chosen from the keyboard (`detail` 0), focus goes back to the trigger instead of being lost with
   * the removed item.
   */
  onMenuClick(event: MouseEvent) {
    const target = event.target as HTMLElement | null;
    if (target?.closest('[role="menuitem"]')) {
      this.close();
      if (event.detail === 0) {
        this.trigger()?.focus();
      }
    }
  }

  @HostListener('keydown.escape', ['$event'])
  onEscape(event: Event) {
    if (this.isOpen) {
      event.stopPropagation();
      this.close();
      this.trigger()?.focus();
    }
  }

  /** Closes the menu once keyboard focus leaves the dropdown. */
  @HostListener('focusout', ['$event'])
  onFocusOut(event: FocusEvent) {
    const next = event.relatedTarget as Node | null;
    if (this.isOpen && next && !this.eRef.nativeElement.contains(next)) {
      this.close();
    }
  }

  /** Clicks inside the dropdown must not reach the clickable row that hosts it. */
  @HostListener('click', ['$event'])
  onHostClick(event: Event) {
    event.stopPropagation();
  }

  @HostListener('document:click', ['$event'])
  clickOutside(event: Event) {
    if (!this.eRef.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  private close() {
    this.isOpen = false;
    if (DropdownComponent.activeDropdown === this) {
      DropdownComponent.activeDropdown = null;
    }
  }

  private trigger(): HTMLElement | null {
    return this.eRef.nativeElement.querySelector<HTMLElement>('button[aria-haspopup]');
  }

  private menuItems(): HTMLElement[] {
    const menu = this.eRef.nativeElement.querySelector('[role="menu"]');
    return menu ? Array.from(menu.querySelectorAll<HTMLElement>(FOCUSABLE_ITEMS)) : [];
  }

  /** The projected actions are plain buttons and links, so give them the role their menu expects. */
  private markMenuItems() {
    this.cdr.detectChanges();
    this.menuItems().forEach((item) => item.setAttribute('role', 'menuitem'));
  }
}
