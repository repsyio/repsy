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

import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';

import { ToastComponent } from './toast.component';
import { ToastService } from './toast.service';

describe('ToastComponent', () => {
  let fixture: ComponentFixture<ToastComponent>;
  let service: ToastService;

  const stack = (): HTMLElement => fixture.nativeElement.querySelector('[data-testid="toast-stack"]');
  const toasts = (): HTMLElement[] => Array.from(fixture.nativeElement.querySelectorAll('[data-testid="toast"]'));
  const fire = (target: Element, type: string) => {
    target.dispatchEvent(new Event(type, { bubbles: type === 'focusin' || type === 'focusout' }));
    fixture.detectChanges();
  };

  beforeEach(() => {
    spyOn(console, 'error');
    TestBed.configureTestingModule({ imports: [ToastComponent] });
    fixture = TestBed.createComponent(ToastComponent);
    service = TestBed.inject(ToastService);
    fixture.detectChanges();
  });

  afterEach(() => {
    service.toasts.slice().forEach((toast) => service.remove(toast.id));
  });

  it('keeps a polite live region on screen even when there is no toast', () => {
    expect(stack().getAttribute('role')).toBe('status');
    expect(stack().getAttribute('aria-live')).toBe('polite');
    expect(toasts().length).toBe(0);
  });

  it('gives error toasts role alert and success toasts none', () => {
    service.show('Saved');
    service.show('Boom', 'error');
    fixture.detectChanges();

    const [success, error] = toasts();
    expect(success.hasAttribute('role')).toBeFalse();
    expect(error.getAttribute('role')).toBe('alert');
    expect(error.getAttribute('data-toast-type')).toBe('error');
  });

  it('names the close button and hides the decorative icons', () => {
    service.show('Saved');
    fixture.detectChanges();

    const close = fixture.nativeElement.querySelector('[data-testid="toast-close"]') as HTMLButtonElement;
    expect(close.getAttribute('aria-label')).toBe('Dismiss notification');
    expect(close.getAttribute('type')).toBe('button');
    fixture.nativeElement.querySelectorAll('[data-testid="toast"] svg').forEach((svg: Element) => {
      expect(svg.getAttribute('aria-hidden')).toBe('true');
    });
  });

  it('removes a toast through its close button', () => {
    service.show('Saved');
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-testid="toast-close"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(toasts().length).toBe(0);
  });

  it('keeps an error toast while it is hovered and lets it go on leave', fakeAsync(() => {
    service.show('Boom', 'error');
    fixture.detectChanges();

    tick(6000);
    fire(toasts()[0], 'mouseenter');
    tick(30000);
    expect(service.toasts.length).toBe(1);

    fire(toasts()[0], 'mouseleave');
    tick(1000);
    expect(service.toasts.length).toBe(0);
  }));

  it('keeps an error toast while focus is inside it, and while either hover or focus remains', fakeAsync(() => {
    service.show('Boom', 'error');
    fixture.detectChanges();

    fire(toasts()[0], 'focusin');
    fire(toasts()[0], 'mouseenter');
    fire(toasts()[0], 'mouseleave');
    tick(30000);
    expect(service.toasts.length).toBe(1);

    fire(toasts()[0], 'focusout');
    tick(7000);
    expect(service.toasts.length).toBe(0);
  }));

  it('does not hold a success toast', fakeAsync(() => {
    service.show('Saved');
    fixture.detectChanges();

    fire(toasts()[0], 'mouseenter');
    tick(3000);
    expect(service.toasts.length).toBe(0);
  }));
});
