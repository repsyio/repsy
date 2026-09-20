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

import { discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { Observable, of } from 'rxjs';

import { pollUntilTerminal } from './poll-until-terminal.util';

describe('pollUntilTerminal', () => {
  function sequence(states: string[]): { fetch: () => Observable<string>; calls: () => number } {
    let calls = 0;
    return {
      fetch: () => of(states[Math.min(calls++, states.length - 1)]),
      calls: () => calls,
    };
  }

  it('fetches immediately, then on every interval, until a terminal result', fakeAsync(() => {
    const { fetch, calls } = sequence(['RUNNING', 'RUNNING', 'DONE']);
    const seen: string[] = [];
    let completed = false;

    pollUntilTerminal(fetch, (s) => s === 'DONE', 1000).subscribe({
      next: (s) => seen.push(s),
      complete: () => (completed = true),
    });

    tick(0);
    expect(seen).toEqual(['RUNNING']);
    tick(1000);
    expect(seen).toEqual(['RUNNING', 'RUNNING']);
    expect(completed).toBeFalse();
    tick(1000);
    expect(seen).toEqual(['RUNNING', 'RUNNING', 'DONE']);
    expect(completed).toBeTrue();
    expect(calls()).toBe(3);
  }));

  it('emits the terminal result and stops fetching afterwards', fakeAsync(() => {
    const { fetch, calls } = sequence(['DONE']);
    const seen: string[] = [];

    pollUntilTerminal(fetch, (s) => s === 'DONE', 1000).subscribe((s) => seen.push(s));

    tick(10_000);
    expect(seen).toEqual(['DONE']);
    expect(calls()).toBe(1);
  }));

  it('polls every 3 seconds by default', fakeAsync(() => {
    const { fetch, calls } = sequence(['RUNNING']);

    const subscription = pollUntilTerminal(fetch, () => false).subscribe();

    tick(0);
    expect(calls()).toBe(1);
    tick(2999);
    expect(calls()).toBe(1);
    tick(1);
    expect(calls()).toBe(2);

    subscription.unsubscribe();
    discardPeriodicTasks();
  }));

  it('stops fetching once unsubscribed', fakeAsync(() => {
    const { fetch, calls } = sequence(['RUNNING']);

    const subscription = pollUntilTerminal(fetch, () => false, 1000).subscribe();
    tick(1000);
    subscription.unsubscribe();
    tick(5000);

    expect(calls()).toBe(2);
  }));
});
