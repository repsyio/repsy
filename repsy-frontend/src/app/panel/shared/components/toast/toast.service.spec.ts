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

import { fakeAsync, tick } from '@angular/core/testing';

import { ToastService } from './toast.service';

describe('ToastService', () => {
  let service: ToastService;

  beforeEach(() => {
    service = new ToastService();
    spyOn(console, 'error');
  });

  it('shows a success toast by default', fakeAsync(() => {
    service.show('Saved');

    expect(service.toasts.length).toBe(1);
    expect(service.toasts[0].message).toBe('Saved');
    expect(service.toasts[0].type).toBe('success');
    expect(console.error).not.toHaveBeenCalled();

    tick(3000);
  }));

  it('logs an error toast to the console', fakeAsync(() => {
    service.show('Boom', 'error');

    expect(service.toasts[0].type).toBe('error');
    expect(console.error).toHaveBeenCalledOnceWith('Boom');

    tick(3000);
  }));

  it('removes a toast once its duration has passed, 3 seconds by default', fakeAsync(() => {
    service.show('Saved');

    tick(2999);
    expect(service.toasts.length).toBe(1);
    tick(1);
    expect(service.toasts.length).toBe(0);
  }));

  it('honours a custom duration', fakeAsync(() => {
    service.show('Saved', 'success', 500);

    tick(499);
    expect(service.toasts.length).toBe(1);
    tick(1);
    expect(service.toasts.length).toBe(0);
  }));

  it('gives every toast its own id, and removes only the one that expired', fakeAsync(() => {
    service.show('first', 'success', 1000);
    service.show('second', 'success', 2000);

    expect(service.toasts[0].id).not.toBe(service.toasts[1].id);

    tick(1000);
    expect(service.toasts.map((t) => t.message)).toEqual(['second']);
    tick(1000);
    expect(service.toasts).toEqual([]);
  }));

  it('never keeps more than three toasts on screen', fakeAsync(() => {
    ['a', 'b', 'c', 'd', 'e'].forEach((message) => service.show(message));

    expect(service.toasts.length).toBe(3);

    tick(3000);
    expect(service.toasts).toEqual([]);
  }));

  it('evicts the oldest toasts and keeps the three newest, in order, when a burst of five arrives', fakeAsync(() => {
    ['a', 'b', 'c', 'd', 'e'].forEach((message) => service.show(message));

    expect(service.toasts.map((t) => t.message)).toEqual(['c', 'd', 'e']);

    tick(3000);
    expect(service.toasts).toEqual([]);
  }));

  it('keeps all three toasts, in order, while the limit is not exceeded', fakeAsync(() => {
    ['a', 'b', 'c'].forEach((message) => service.show(message));

    expect(service.toasts.map((t) => t.message)).toEqual(['a', 'b', 'c']);

    tick(3000);
    expect(service.toasts).toEqual([]);
  }));

  it('leaves the surviving toasts untouched when the timer of an evicted toast fires', fakeAsync(() => {
    service.show('a', 'success', 1000);
    ['b', 'c', 'd', 'e'].forEach((message, i) => service.show(message, 'success', 2000 + i * 1000));
    expect(service.toasts.map((t) => t.message)).toEqual(['c', 'd', 'e']);

    // 'a' and 'b' were evicted; their timers fire at 1000ms and 2000ms and must not remove anything else.
    tick(1000);
    expect(service.toasts.map((t) => t.message)).toEqual(['c', 'd', 'e']);
    tick(1000);
    expect(service.toasts.map((t) => t.message)).toEqual(['c', 'd', 'e']);

    // The survivors expire on their own timers: c at 3000ms, d at 4000ms, e at 5000ms.
    tick(1000);
    expect(service.toasts.map((t) => t.message)).toEqual(['d', 'e']);
    tick(1000);
    expect(service.toasts.map((t) => t.message)).toEqual(['e']);
    tick(1000);
    expect(service.toasts).toEqual([]);
  }));

  it('removes a toast by id, and ignores an unknown id', fakeAsync(() => {
    service.show('first');
    service.show('second');
    const [first, second] = service.toasts;

    service.remove(first.id);
    expect(service.toasts).toEqual([second]);

    service.remove(9999);
    expect(service.toasts).toEqual([second]);

    tick(3000);
  }));
});
