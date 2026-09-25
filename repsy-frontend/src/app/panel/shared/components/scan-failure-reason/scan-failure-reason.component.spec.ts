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

import { ScanFailureReasonComponent } from './scan-failure-reason.component';

describe('ScanFailureReasonComponent', () => {
  let fixture: ComponentFixture<ScanFailureReasonComponent>;

  function render(message: string | null | undefined): HTMLElement {
    fixture.componentRef.setInput('message', message);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ScanFailureReasonComponent] });
    fixture = TestBed.createComponent(ScanFailureReasonComponent);
  });

  it('shows the reason', () => {
    const el = render('stub scanner: simulated scan failure');

    expect(el.querySelector('[data-testid="scan-failure-reason"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="scan-failure-reason-text"]')?.textContent).toBe(
      'stub scanner: simulated scan failure',
    );
  });

  it('renders nothing without a message', () => {
    expect(render(null).querySelector('[data-testid="scan-failure-reason"]')).toBeNull();
    expect(render('  ').querySelector('[data-testid="scan-failure-reason"]')).toBeNull();
  });

  it('never renders the message as HTML', () => {
    const el = render('<img src=x onerror=alert(1)>');

    expect(el.querySelector('img')).toBeNull();
    expect(el.querySelector('[data-testid="scan-failure-reason-text"]')?.textContent).toBe(
      '<img src=x onerror=alert(1)>',
    );
  });

  it('cuts a message longer than the limit', () => {
    const text = render('y'.repeat(500)).querySelector('[data-testid="scan-failure-reason-text"]')?.textContent ?? '';

    expect(text.length).toBeLessThanOrEqual(200);
    expect(text.endsWith('...')).toBeTrue();
  });
});
