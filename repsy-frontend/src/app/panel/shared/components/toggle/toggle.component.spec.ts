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

import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ToggleComponent } from './toggle.component';

describe('ToggleComponent', () => {
  let fixture: ComponentFixture<ToggleComponent>;
  let component: ToggleComponent;

  const input = (): HTMLInputElement => fixture.nativeElement.querySelector('input');
  const label = (): HTMLLabelElement => fixture.nativeElement.querySelector('label');

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ToggleComponent] });
    fixture = TestBed.createComponent(ToggleComponent);
    component = fixture.componentInstance;
    component.checked = false;
    component.checkedLabel = 'Enabled';
    component.uncheckedLabel = 'Disabled';
    fixture.detectChanges();
  });

  it('exposes a labelled switch to assistive technology', () => {
    expect(input().getAttribute('role')).toBe('switch');
    expect(input().getAttribute('aria-checked')).toBe('false');
    expect(label().contains(input())).toBeTrue();
    expect(label().textContent).toContain('Disabled');
  });

  it('updates aria-checked and the label text when toggled', () => {
    input().click();
    fixture.detectChanges();

    expect(component.checked).toBeTrue();
    expect(input().getAttribute('aria-checked')).toBe('true');
    expect(label().textContent).toContain('Enabled');
  });

  it('emits both outputs with the new state', () => {
    const changes: boolean[] = [];
    const switches: boolean[] = [];
    component.checkedChange.subscribe((value) => changes.push(value));
    component.switch.subscribe((value) => switches.push(value));

    input().click();
    input().click();

    expect(changes).toEqual([true, false]);
    expect(switches).toEqual([true, false]);
  });

  it('can be toggled from the keyboard, since Space clicks a focused checkbox', () => {
    input().focus();
    expect(document.activeElement).toBe(input());
    input().click();

    expect(component.checked).toBeTrue();
  });

  it('toggles when the text label is clicked', () => {
    (fixture.nativeElement.querySelector('label > span:last-child') as HTMLElement).click();

    expect(component.checked).toBeTrue();
  });

  it('shows the static label regardless of state', () => {
    component.staticLabel = 'Always';
    fixture.detectChanges();
    expect(label().textContent).toContain('Always');
  });

  it('does nothing while disabled', () => {
    component.disabled = true;
    fixture.detectChanges();
    const emitted = jasmine.createSpy('emitted');
    component.checkedChange.subscribe(emitted);

    expect(input().disabled).toBeTrue();
    component.toggle();

    expect(component.checked).toBeFalse();
    expect(emitted).not.toHaveBeenCalled();
  });
});
