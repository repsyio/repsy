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

import { ComponentFixture, fakeAsync, flushMicrotasks, TestBed, tick } from '@angular/core/testing';

import { CopyClipboardComponent } from './copy-clipboard.component';

describe('CopyClipboardComponent', () => {
  let fixture: ComponentFixture<CopyClipboardComponent>;

  const button = (): HTMLButtonElement => fixture.nativeElement.querySelector('[data-testid="copy-button"]');

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CopyClipboardComponent] });
    fixture = TestBed.createComponent(CopyClipboardComponent);
    fixture.componentInstance.text = 'mvn install';
    fixture.detectChanges();
  });

  it('is a named, non-submitting button with a decorative icon', () => {
    expect(button().getAttribute('aria-label')).toBe('Copy to clipboard');
    expect(button().getAttribute('type')).toBe('button');
    expect(button().querySelector('svg').getAttribute('aria-hidden')).toBe('true');
  });

  it('copies the text and reflects it in its name and data-copied for a second', fakeAsync(() => {
    const write = spyOn(navigator.clipboard, 'writeText').and.returnValue(Promise.resolve());

    button().click();
    flushMicrotasks();
    fixture.detectChanges();

    expect(write).toHaveBeenCalledOnceWith('mvn install');
    expect(button().getAttribute('data-copied')).toBe('true');
    expect(button().getAttribute('aria-label')).toBe('Copied to clipboard');

    tick(1000);
    fixture.detectChanges();
    expect(button().getAttribute('aria-label')).toBe('Copy to clipboard');
  }));
});
