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

import { Sort } from '../../dtos/sort';
import { SelectorComponent } from '../selector/selector.component';
import { SortSelectorComponent } from '../sort-selector/sort-selector.component';
import { DropdownComponent } from './dropdown.component';

const NEWEST: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
const OLDEST: Sort = { name: 'Oldest', column: 'createdAt', type: 'ASC' };

@Component({
  standalone: true,
  imports: [DropdownComponent, SelectorComponent, SortSelectorComponent],
  template: `
    <app-selector [options]="types" selectedOption="all" />
    <app-sort-selector [options]="sorts" />
    <app-dropdown><button id="action">Delete</button></app-dropdown>
  `,
})
class HostComponent {
  types = ['all', 'snapshots'];
  sorts = [NEWEST, OLDEST];
}

/** Only one menu is open at a time (RPS-1565): a row menu and the selectors close each other. */
describe('Menu coordination', () => {
  let fixture: ComponentFixture<HostComponent>;

  const q = (testId: string): HTMLElement | null => fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  const click = (testId: string): void => {
    q(testId)!.click();
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('opening the selector closes an open row menu', () => {
    click('dropdown-toggle');
    expect(q('dropdown-menu')).not.toBeNull();

    click('selector-toggle');

    expect(q('selector-menu')).not.toBeNull();
    expect(q('dropdown-menu')).toBeNull();
  });

  it('opening the sort selector closes an open row menu', () => {
    click('dropdown-toggle');
    expect(q('dropdown-menu')).not.toBeNull();

    click('sort-selector-toggle');

    expect(q('sort-selector-menu')).not.toBeNull();
    expect(q('dropdown-menu')).toBeNull();
  });

  it('opening the row menu closes an open selector and an open sort selector', () => {
    click('selector-toggle');
    click('dropdown-toggle');
    expect(q('selector-menu')).toBeNull();
    expect(q('dropdown-menu')).not.toBeNull();

    click('sort-selector-toggle');
    click('dropdown-toggle');

    expect(q('sort-selector-menu')).toBeNull();
    expect(q('dropdown-menu')).not.toBeNull();
  });

  it('opening the sort selector closes an open selector', () => {
    click('selector-toggle');

    click('sort-selector-toggle');

    expect(q('selector-menu')).toBeNull();
    expect(q('sort-selector-menu')).not.toBeNull();
  });
});
