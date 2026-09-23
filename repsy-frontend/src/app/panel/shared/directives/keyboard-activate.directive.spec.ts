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

import { KeyboardActivateDirective } from './keyboard-activate.directive';

@Component({
  standalone: true,
  imports: [KeyboardActivateDirective],
  template: `
    <button type="button" id="row" appKeyboardActivate (click)="clicks = clicks + 1">
      <a id="inner" href="javascript:void(0)">link</a>
    </button>
    <div id="custom" appKeyboardActivate role="link" tabindex="-1"></div>
  `,
})
class HostComponent {
  public clicks = 0;
}

describe('KeyboardActivateDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  const row = (): HTMLElement => fixture.debugElement.query(By.css('#row')).nativeElement;

  const press = (target: HTMLElement, key: string): KeyboardEvent => {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
    target.dispatchEvent(event);
    return event;
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('adds the button role and joins the tab order', () => {
    expect(row().getAttribute('role')).toBe('button');
    expect(row().getAttribute('tabindex')).toBe('0');
  });

  it('keeps an explicit role and tabindex from the template', () => {
    const custom: HTMLElement = fixture.debugElement.query(By.css('#custom')).nativeElement;
    expect(custom.getAttribute('role')).toBe('link');
    expect(custom.getAttribute('tabindex')).toBe('-1');
  });

  it('clicks the element on Enter', () => {
    const event = press(row(), 'Enter');
    expect(host.clicks).toBe(1);
    expect(event.defaultPrevented).toBeTrue();
  });

  it('clicks the element on Space and stops the page from scrolling', () => {
    const event = press(row(), ' ');
    expect(host.clicks).toBe(1);
    expect(event.defaultPrevented).toBeTrue();
  });

  it('ignores other keys', () => {
    const event = press(row(), 'a');
    press(row(), 'Tab');
    expect(host.clicks).toBe(0);
    expect(event.defaultPrevented).toBeFalse();
  });

  it('leaves keys pressed on a nested control alone', () => {
    const inner: HTMLElement = fixture.debugElement.query(By.css('#inner')).nativeElement;
    const event = press(inner, 'Enter');
    expect(host.clicks).toBe(0);
    expect(event.defaultPrevented).toBeFalse();
  });
});
