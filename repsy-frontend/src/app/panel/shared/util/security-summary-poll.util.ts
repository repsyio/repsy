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
import { EMPTY, Observable, timer } from 'rxjs';
import { catchError, expand, switchMap } from 'rxjs/operators';

import { ScanStatus } from '../../../../generated/api';
import { isRescanInProgress } from './rescan-status.util';

/** The part of a security summary entry that tells whether a scan is still unfinished. */
export interface ScanProgressEntry {
  latestScanStatus?: ScanStatus | null;
  rescanInProgressCount?: number | null;
  unscannedInProgressCount?: number | null;
}

export interface SecuritySummaryPollOptions {
  /** Wait before the first re-fetch. */
  intervalMs?: number;
  /** The wait grows by half after every re-fetch, up to this. */
  maxIntervalMs?: number;
  /** Re-fetches allowed after the first fetch, so a scan that never finishes cannot poll forever. */
  maxPolls?: number;
}

export const SECURITY_SUMMARY_POLL_INTERVAL_MS = 10_000;
export const SECURITY_SUMMARY_POLL_MAX_INTERVAL_MS = 60_000;
export const SECURITY_SUMMARY_POLL_MAX_POLLS = 30;

/** True when a scan behind the entry (a first scan, a rescan or the version's newest scan) is unfinished. */
export function isScanInProgress(entry: ScanProgressEntry | null | undefined): boolean {
  return (
    !!entry &&
    ((entry.unscannedInProgressCount ?? 0) > 0 ||
      (entry.rescanInProgressCount ?? 0) > 0 ||
      isRescanInProgress(entry.latestScanStatus))
  );
}

/**
 * Fetches a security summary and, while any of its entries still reports an unfinished scan,
 * fetches it again after a growing delay, emitting every result. The stream completes as soon as
 * nothing is in progress or the re-fetch budget is spent; unsubscribing cancels a pending wait or
 * request. A failed re-fetch ends the polling and leaves the last emitted summary in place, a
 * failed first fetch errors as usual.
 */
export function pollSecuritySummary<T extends ScanProgressEntry>(
  fetchFn: () => Observable<Record<string, T>>,
  options: SecuritySummaryPollOptions = {},
): Observable<Record<string, T>> {
  const {
    intervalMs = SECURITY_SUMMARY_POLL_INTERVAL_MS,
    maxIntervalMs = SECURITY_SUMMARY_POLL_MAX_INTERVAL_MS,
    maxPolls = SECURITY_SUMMARY_POLL_MAX_POLLS,
  } = options;

  return fetchFn().pipe(
    expand((summary, pollsDone) =>
      pollsDone < maxPolls && Object.values(summary).some(isScanInProgress)
        ? timer(Math.min(intervalMs * 1.5 ** pollsDone, maxIntervalMs)).pipe(
            switchMap(() => fetchFn()),
            catchError(() => EMPTY),
          )
        : EMPTY,
    ),
  );
}
