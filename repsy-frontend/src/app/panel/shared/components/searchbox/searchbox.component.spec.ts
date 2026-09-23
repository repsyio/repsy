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

import { SearchboxComponent } from './searchbox.component';

describe('SearchboxComponent', () => {
  let fixture: ComponentFixture<SearchboxComponent>;
  let component: SearchboxComponent;

  const input = (): HTMLInputElement => fixture.nativeElement.querySelector('input');

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SearchboxComponent] });
    fixture = TestBed.createComponent(SearchboxComponent);
    component = fixture.componentInstance;
  });

  it('names the input after its placeholder by default', () => {
    component.placeholder = 'Search packages';
    fixture.detectChanges();

    expect(input().getAttribute('aria-label')).toBe('Search packages');
  });

  it('prefers an explicit accessible name', () => {
    component.placeholder = 'Search packages';
    component.ariaLabel = 'Filter packages by name';
    fixture.detectChanges();

    expect(input().getAttribute('aria-label')).toBe('Filter packages by name');
  });

  it('still has a name when no placeholder is given', () => {
    fixture.detectChanges();

    expect(input().getAttribute('aria-label')).toBe('Search');
  });

  it('hides the decorative icon from assistive technology', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('svg').getAttribute('aria-hidden')).toBe('true');
  });

  it('emits the typed text', () => {
    fixture.detectChanges();
    const emitted: string[] = [];
    component.filter.subscribe((value) => emitted.push(value));

    input().value = 'lodash';
    input().dispatchEvent(new Event('input'));

    expect(emitted).toEqual(['lodash']);
  });
});
