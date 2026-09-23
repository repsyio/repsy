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
import { Observable, of, Subject, throwError } from 'rxjs';

import { ScanStatus } from '../../../../generated/api';
import { isScanInProgress, pollSecuritySummary, ScanProgressEntry } from './security-summary-poll.util';

type Summary = Record<string, ScanProgressEntry>;

const SCANNING: Summary = { lib: { unscannedInProgressCount: 1 } };
const DONE: Summary = { lib: { unscannedInProgressCount: 0, rescanInProgressCount: 0 } };

describe('isScanInProgress', () => {
  it('flags a first scan or a rescan that is unfinished', () => {
    expect(isScanInProgress({ unscannedInProgressCount: 1 })).toBeTrue();
    expect(isScanInProgress({ rescanInProgressCount: 2 })).toBeTrue();
  });

  it('flags a version whose newest scan is pending, queued or running', () => {
    for (const status of [ScanStatus.Pending, ScanStatus.Queued, ScanStatus.Running]) {
      expect(isScanInProgress({ latestScanStatus: status })).toBeTrue();
    }
  });

  it('does not flag finished, failed or absent scans', () => {
    expect(isScanInProgress({ latestScanStatus: ScanStatus.Completed })).toBeFalse();
    expect(isScanInProgress({ latestScanStatus: ScanStatus.Failed, unscannedInProgressCount: 0 })).toBeFalse();
    expect(isScanInProgress({})).toBeFalse();
    expect(isScanInProgress(null)).toBeFalse();
    expect(isScanInProgress(undefined)).toBeFalse();
  });
});

describe('pollSecuritySummary', () => {
  function sequence(results: Summary[]): { fetch: () => Observable<Summary>; calls: () => number } {
    let calls = 0;
    return {
      fetch: () => of(results[Math.min(calls++, results.length - 1)]),
      calls: () => calls,
    };
  }

  it('fetches once and completes when nothing is in progress', fakeAsync(() => {
    const { fetch, calls } = sequence([DONE]);
    let completed = false;
    const seen: Summary[] = [];

    pollSecuritySummary(fetch).subscribe({ next: (s) => seen.push(s), complete: () => (completed = true) });

    tick(600_000);
    expect(seen).toEqual([DONE]);
    expect(completed).toBeTrue();
    expect(calls()).toBe(1);
  }));

  it('fetches again after 10 seconds while a scan is in progress and stops once it is done', fakeAsync(() => {
    const { fetch, calls } = sequence([SCANNING, SCANNING, DONE]);
    const seen: Summary[] = [];
    let completed = false;

    pollSecuritySummary(fetch).subscribe({ next: (s) => seen.push(s), complete: () => (completed = true) });

    expect(calls()).toBe(1);
    tick(9_999);
    expect(calls()).toBe(1);
    tick(1);
    expect(calls()).toBe(2);
    expect(completed).toBeFalse();
    tick(15_000);
    expect(calls()).toBe(3);
    expect(seen).toEqual([SCANNING, SCANNING, DONE]);
    expect(completed).toBeTrue();

    tick(600_000);
    expect(calls()).toBe(3);
  }));

  it('waits longer after every re-fetch, up to the maximum', fakeAsync(() => {
    const { fetch, calls } = sequence([SCANNING]);

    const subscription = pollSecuritySummary(fetch, {
      intervalMs: 1_000,
      maxIntervalMs: 2_000,
      maxPolls: 10,
    }).subscribe();

    tick(999);
    expect(calls()).toBe(1);
    tick(1);
    expect(calls()).toBe(2);
    tick(1_499);
    expect(calls()).toBe(2);
    tick(1);
    expect(calls()).toBe(3);
    tick(1_999);
    expect(calls()).toBe(3);
    tick(1);
    expect(calls()).toBe(4);
    tick(2_000);
    expect(calls()).toBe(5);

    subscription.unsubscribe();
    discardPeriodicTasks();
  }));

  it('gives up after the maximum number of re-fetches when a scan never finishes', fakeAsync(() => {
    const { fetch, calls } = sequence([SCANNING]);
    let completed = false;

    pollSecuritySummary(fetch, { intervalMs: 1_000, maxIntervalMs: 1_000, maxPolls: 3 }).subscribe({
      complete: () => (completed = true),
    });

    tick(600_000);
    expect(calls()).toBe(4);
    expect(completed).toBeTrue();
  }));

  it('stops fetching once unsubscribed', fakeAsync(() => {
    const { fetch, calls } = sequence([SCANNING]);

    const subscription = pollSecuritySummary(fetch).subscribe();
    tick(10_000);
    expect(calls()).toBe(2);

    subscription.unsubscribe();
    tick(600_000);
    expect(calls()).toBe(2);
  }));

  it('cancels a request that is still in flight when unsubscribed', fakeAsync(() => {
    const inFlight = new Subject<Summary>();
    let calls = 0;
    const fetch = (): Observable<Summary> => (++calls === 1 ? of(SCANNING) : inFlight);
    const seen: Summary[] = [];

    const subscription = pollSecuritySummary(fetch).subscribe((s) => seen.push(s));
    tick(10_000);
    expect(inFlight.observed).toBeTrue();

    subscription.unsubscribe();
    expect(inFlight.observed).toBeFalse();
    inFlight.next(DONE);
    expect(seen).toEqual([SCANNING]);
  }));

  it('keeps the last summary and stops when a re-fetch fails', fakeAsync(() => {
    let calls = 0;
    const fetch = (): Observable<Summary> => (++calls === 1 ? of(SCANNING) : throwError(() => new Error('boom')));
    const seen: Summary[] = [];
    let errored = false;
    let completed = false;

    pollSecuritySummary(fetch).subscribe({
      next: (s) => seen.push(s),
      error: () => (errored = true),
      complete: () => (completed = true),
    });

    tick(600_000);
    expect(seen).toEqual([SCANNING]);
    expect(errored).toBeFalse();
    expect(completed).toBeTrue();
    expect(calls).toBe(2);
  }));

  it('errors when the first fetch fails', () => {
    let errored = false;

    pollSecuritySummary(() => throwError(() => new Error('boom'))).subscribe({ error: () => (errored = true) });

    expect(errored).toBeTrue();
  });
});
