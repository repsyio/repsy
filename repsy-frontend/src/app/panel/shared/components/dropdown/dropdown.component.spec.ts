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

import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';

import { DropdownComponent } from './dropdown.component';

@Component({
  standalone: true,
  imports: [DropdownComponent],
  template: `
    <button type="button" id="row" (click)="rowClicks = rowClicks + 1">
      <app-dropdown id="first" [label]="'Actions'">
        <button id="one" (click)="picked = 'one'">One</button>
        <button id="two" disabled>Two</button>
        <a id="three" href="javascript:void(0)">Three</a>
      </app-dropdown>
    </button>
    <app-dropdown id="second"><button id="other">Other</button></app-dropdown>
    <button id="outside">outside</button>
  `,
})
class HostComponent {
  public rowClicks = 0;
  public picked = '';
}

describe('DropdownComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  const el = (selector: string): HTMLElement => fixture.debugElement.query(By.css(selector)).nativeElement;
  const trigger = (id: string): HTMLButtonElement => el(`#${id} button[aria-haspopup]`) as HTMLButtonElement;
  const menu = (id: string): HTMLElement | null => fixture.nativeElement.querySelector(`#${id} [role="menu"]`);
  const key = (target: HTMLElement, name: string): KeyboardEvent => {
    const event = new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true });
    target.dispatchEvent(event);
    fixture.detectChanges();
    return event;
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('renders a labelled trigger that is collapsed by default', () => {
    const button = trigger('first');
    expect(button.getAttribute('aria-label')).toBe('Actions');
    expect(button.getAttribute('aria-haspopup')).toBe('menu');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.hasAttribute('aria-controls')).toBeFalse();
    expect(menu('first')).toBeNull();
  });

  it('defaults the label when none is given', () => {
    expect(trigger('second').getAttribute('aria-label')).toBe('More options');
  });

  it('opens on a click and reflects that in aria-expanded and aria-controls', () => {
    trigger('first').click();
    fixture.detectChanges();

    const open = menu('first');
    expect(open).not.toBeNull();
    expect(open.getAttribute('aria-label')).toBe('Actions');
    expect(trigger('first').getAttribute('aria-expanded')).toBe('true');
    expect(trigger('first').getAttribute('aria-controls')).toBe(open.id);
  });

  it('closes on a second click', () => {
    trigger('first').click();
    fixture.detectChanges();
    trigger('first').click();
    fixture.detectChanges();

    expect(menu('first')).toBeNull();
    expect(trigger('first').getAttribute('aria-expanded')).toBe('false');
  });

  it('gives the projected actions the menuitem role', () => {
    trigger('first').click();
    fixture.detectChanges();

    expect(el('#one').getAttribute('role')).toBe('menuitem');
    expect(el('#three').getAttribute('role')).toBe('menuitem');
  });

  it('does not let clicks reach the row that hosts the dropdown', () => {
    const host = fixture.componentInstance;
    trigger('first').click();
    fixture.detectChanges();
    el('#one').click();

    expect(host.picked).toBe('one');
    expect(host.rowClicks).toBe(0);
  });

  it('opens on ArrowDown and focuses the first enabled item', () => {
    const event = key(trigger('first'), 'ArrowDown');

    expect(event.defaultPrevented).toBeTrue();
    expect(menu('first')).not.toBeNull();
    expect(document.activeElement).toBe(el('#one'));
  });

  it('moves focus with the arrow keys, wrapping and skipping disabled items', () => {
    key(trigger('first'), 'ArrowDown');

    key(el('#one'), 'ArrowDown');
    expect(document.activeElement).toBe(el('#three'));
    key(el('#three'), 'ArrowDown');
    expect(document.activeElement).toBe(el('#one'));
    key(el('#one'), 'ArrowUp');
    expect(document.activeElement).toBe(el('#three'));
    key(el('#three'), 'Home');
    expect(document.activeElement).toBe(el('#one'));
    key(el('#one'), 'End');
    expect(document.activeElement).toBe(el('#three'));
  });

  it('ignores unrelated keys inside the menu', () => {
    key(trigger('first'), 'ArrowDown');
    const event = key(el('#one'), 'a');

    expect(event.defaultPrevented).toBeFalse();
    expect(document.activeElement).toBe(el('#one'));
  });

  it('closes on Escape and returns focus to the trigger', () => {
    key(trigger('first'), 'ArrowDown');
    key(el('#one'), 'Escape');

    expect(menu('first')).toBeNull();
    expect(document.activeElement).toBe(trigger('first'));
  });

  it('ignores Escape while closed', () => {
    const event = key(trigger('first'), 'Escape');
    expect(event.defaultPrevented).toBeFalse();
    expect(menu('first')).toBeNull();
  });

  it('closes when a click lands outside', () => {
    trigger('first').click();
    fixture.detectChanges();
    el('#outside').click();
    fixture.detectChanges();

    expect(menu('first')).toBeNull();
  });

  it('closes when keyboard focus leaves the dropdown', () => {
    key(trigger('first'), 'ArrowDown');
    const outside = el('#outside');
    el('#one').dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: outside }));
    fixture.detectChanges();

    expect(menu('first')).toBeNull();
  });

  it('keeps the menu open while focus moves inside the dropdown', () => {
    key(trigger('first'), 'ArrowDown');
    el('#one').dispatchEvent(new FocusEvent('focusout', { bubbles: true, relatedTarget: el('#three') }));
    fixture.detectChanges();

    expect(menu('first')).not.toBeNull();
  });

  it('keeps only one dropdown open at a time', () => {
    trigger('first').click();
    fixture.detectChanges();
    trigger('second').click();
    fixture.detectChanges();

    expect(menu('first')).toBeNull();
    expect(menu('second')).not.toBeNull();
  });

  it('gives every dropdown its own menu id', () => {
    trigger('first').click();
    fixture.detectChanges();
    const first = menu('first').id;
    trigger('second').click();
    fixture.detectChanges();

    expect(menu('second').id).not.toBe(first);
  });

  it('closes after an action is chosen, and after a click on a disabled one it stays', () => {
    trigger('first').click();
    fixture.detectChanges();
    el('#two').click();
    fixture.detectChanges();
    expect(menu('first')).not.toBeNull();

    el('#one').click();
    fixture.detectChanges();

    expect(fixture.componentInstance.picked).toBe('one');
    expect(menu('first')).toBeNull();
    expect(trigger('first').getAttribute('aria-expanded')).toBe('false');
  });

  it('closes after a click on a link item', () => {
    trigger('first').click();
    fixture.detectChanges();
    el('#three').click();
    fixture.detectChanges();

    expect(menu('first')).toBeNull();
  });

  it('gives focus back to the trigger when an action is chosen with the keyboard', () => {
    key(trigger('first'), 'ArrowDown');
    // a keyboard-activated click carries detail 0, a pointer click detail 1
    el('#one').dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }));
    fixture.detectChanges();

    expect(menu('first')).toBeNull();
    expect(document.activeElement).toBe(trigger('first'));
  });

  it('keeps the menu open when the click lands on the menu but not on an action', () => {
    trigger('first').click();
    fixture.detectChanges();
    menu('first').click();
    fixture.detectChanges();

    expect(menu('first')).not.toBeNull();
  });

  it('is not a button itself: its trigger is the only button around the menu', () => {
    trigger('second').click();
    fixture.detectChanges();

    expect(el('#second [data-testid="dropdown"]').tagName).toBe('DIV');
    expect(menu('second').closest('button')).toBeNull();
    expect(trigger('second').closest('button')).toBe(trigger('second'));
  });
});
