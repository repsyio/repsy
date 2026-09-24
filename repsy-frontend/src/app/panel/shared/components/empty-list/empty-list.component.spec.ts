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

import { EmptyListComponent } from './empty-list.component';

describe('EmptyListComponent', () => {
  let fixture: ComponentFixture<EmptyListComponent>;

  const text = (): string => fixture.nativeElement.textContent as string;
  const message = (): HTMLElement | null => fixture.nativeElement.querySelector('[data-testid="empty-list-message"]');

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [EmptyListComponent] });
    fixture = TestBed.createComponent(EmptyListComponent);
  });

  it('shows the default hippo text without a message', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="empty-list"]')).not.toBeNull();
    expect(text()).toContain('Your list is empty,');
    expect(text()).toContain('Go ahead, add a new item!');
    expect(message()).toBeNull();
  });

  it('shows the given message instead of the default text', () => {
    fixture.componentRef.setInput('message', 'No user matches “bob”.');
    fixture.detectChanges();

    expect(message()?.textContent).toBe('No user matches “bob”.');
    expect(text()).not.toContain('Your list is empty');
    expect(text()).not.toContain('add a new item');
    expect(fixture.nativeElement.querySelector('[data-testid="empty-list"]')).not.toBeNull();
  });
});
