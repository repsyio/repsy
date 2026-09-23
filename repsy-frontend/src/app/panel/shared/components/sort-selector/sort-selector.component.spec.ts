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

import { Sort } from '../../dto/sort';
import { SortSelectorComponent } from './sort-selector.component';

const NEWEST: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
const OLDEST: Sort = { name: 'Oldest', column: 'createdAt', type: 'ASC' };

describe('SortSelectorComponent', () => {
  let component: SortSelectorComponent;

  beforeEach(() => {
    component = new SortSelectorComponent();
    component.options = [NEWEST, OLDEST];
  });

  describe('ngOnInit', () => {
    it('selects the first option when none is selected', () => {
      component.ngOnInit();

      expect(component.selectedOption).toBe(NEWEST);
    });

    it('keeps the option the parent selected', () => {
      component.selectedOption = OLDEST;

      component.ngOnInit();

      expect(component.selectedOption).toBe(OLDEST);
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
  });

  describe('selectOption', () => {
    it('selects the option and announces it on both outputs', () => {
      const changed: Sort[] = [];
      const chosen: Sort[] = [];
      component.selectedOptionChange.subscribe((option) => changed.push(option));
      component.choose.subscribe((option) => chosen.push(option));

      component.selectOption(OLDEST);

      expect(component.selectedOption).toBe(OLDEST);
      expect(changed).toEqual([OLDEST]);
      expect(chosen).toEqual([OLDEST]);
    });
  });
});
