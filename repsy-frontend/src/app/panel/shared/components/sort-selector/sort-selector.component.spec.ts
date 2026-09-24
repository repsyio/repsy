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

import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Sort } from '../../dto/sort';
import { SortSelectorComponent } from './sort-selector.component';

const NEWEST: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
const OLDEST: Sort = { name: 'Oldest', column: 'createdAt', type: 'ASC' };

describe('SortSelectorComponent', () => {
  let fixture: ComponentFixture<SortSelectorComponent>;
  let component: SortSelectorComponent;

  const toggle = (): HTMLButtonElement =>
    fixture.nativeElement.querySelector('[data-testid="sort-selector-toggle"]') as HTMLButtonElement;
  const menu = (): HTMLElement | null => fixture.nativeElement.querySelector('[data-testid="sort-selector-menu"]');
  const option = (name: string): HTMLButtonElement =>
    fixture.nativeElement.querySelector(`[data-testid="sort-option-${name}"]`) as HTMLButtonElement;
  const click = (target: HTMLElement): void => {
    target.click();
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SortSelectorComponent] });
    fixture = TestBed.createComponent(SortSelectorComponent);
    component = fixture.componentInstance;
    component.options = [NEWEST, OLDEST];
    fixture.detectChanges();
  });

  describe('ngOnInit', () => {
    it('selects the first option when none is selected', () => {
      expect(component.selectedOption).toBe(NEWEST);
      expect(toggle().textContent).toContain('Newest');
    });

    it('keeps the option the parent selected', () => {
      const other = TestBed.createComponent(SortSelectorComponent);
      other.componentInstance.options = [NEWEST, OLDEST];
      other.componentInstance.selectedOption = OLDEST;
      other.detectChanges();

      expect(other.componentInstance.selectedOption).toBe(OLDEST);
    });
  });

  describe('toggleDropdown', () => {
    it('opens and closes the dropdown without letting the click reach the page behind it', () => {
      const event = jasmine.createSpyObj<Event>('Event', ['stopPropagation']);

      component.toggleDropdown(event);
      expect(component.isOpen).toBeTrue();

      component.toggleDropdown(event);
      expect(component.isOpen).toBeFalse();
      expect(event.stopPropagation).toHaveBeenCalledTimes(2);
    });

    it('shows the menu while open', () => {
      expect(menu()).toBeNull();

      click(toggle());
      expect(menu()).not.toBeNull();

      click(toggle());
      expect(menu()).toBeNull();
    });
  });

  describe('selectOption', () => {
    it('selects the option and announces it on both outputs', () => {
      const changed: Sort[] = [];
      const chosen: Sort[] = [];
      component.selectedOptionChange.subscribe((picked) => changed.push(picked));
      component.choose.subscribe((picked) => chosen.push(picked));

      component.selectOption(OLDEST);

      expect(component.selectedOption).toBe(OLDEST);
      expect(changed).toEqual([OLDEST]);
      expect(chosen).toEqual([OLDEST]);
    });

    it('closes the menu once an option is chosen', () => {
      click(toggle());
      expect(menu()).not.toBeNull();

      click(option('Oldest'));

      expect(menu()).toBeNull();
      expect(toggle().textContent).toContain('Oldest');
    });

    it('can be opened again after a choice', () => {
      click(toggle());
      click(option('Oldest'));
      click(toggle());

      expect(menu()).not.toBeNull();
    });
  });

  describe('closing', () => {
    const escape = (): void => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();
    };

    it('closes on Escape and returns the focus to the toggle', () => {
      click(toggle());
      option('Newest').focus();

      escape();

      expect(menu()).toBeNull();
      expect(document.activeElement).toBe(toggle());
    });

    it('ignores Escape while closed', () => {
      toggle().blur();

      escape();

      expect(menu()).toBeNull();
      expect(document.activeElement).not.toBe(toggle());
    });

    it('closes on a click outside of it', () => {
      click(toggle());

      click(document.body);

      expect(menu()).toBeNull();
    });
  });
});
