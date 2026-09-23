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

import { ScanStatus } from '../../../../generated/api';
import { scanStatusLabel } from './scan-status-label.util';

/**
 * Severity and finding counts always describe a version's newest COMPLETED scan. Whenever its
 * newest scan is in any other state, those numbers are the last known ones, so the UI marks them.
 */

export function isRescanInProgress(status: ScanStatus | null | undefined): boolean {
  return status === ScanStatus.Pending || status === ScanStatus.Queued || status === ScanStatus.Running;
}

export function hasRescanFailed(status: ScanStatus | null | undefined): boolean {
  return status === ScanStatus.Failed;
}

/** Tooltip for a badge that shows the last completed scan while the newest one is not completed. */
export function rescanTitle(status: ScanStatus | null | undefined): string {
  if (isRescanInProgress(status)) {
    return 'Rescan in progress. Showing the last completed scan.';
  }

  return hasRescanFailed(status) ? 'Last rescan failed. Showing the last completed scan.' : '';
}

/** Tooltip for a version that has no completed scan, so nothing but its first scan's state can be shown. */
export function firstScanTitle(status: ScanStatus | null | undefined): string {
  if (isRescanInProgress(status)) {
    return 'The first scan is in progress.';
  }

  return hasRescanFailed(status) ? 'The first scan failed.' : '';
}

/**
 * Text for a recent scan whose newest scan is not completed, or an empty string when there is
 * nothing to flag. A version that never completed a scan has no earlier result to show, so it
 * reads as a first scan rather than a rescan.
 */
export function recentScanNote(status: ScanStatus | null | undefined, hasCompletedScan: boolean): string {
  if (!isRescanInProgress(status) && !hasRescanFailed(status)) {
    return '';
  }

  if (!hasCompletedScan) {
    return scanStatusLabel(status);
  }

  return isRescanInProgress(status) ? 'Rescanning...' : 'Last rescan failed';
}

/**
 * Tooltip for a badge that rolls up several versions (a package or a repository). The severity it
 * shows is built from each version's last completed scan, so it names how many of those versions
 * have a newer scan that is unfinished or failed. Empty when there is nothing to flag.
 */
export function rescanCountsTitle(inProgress: number | null | undefined, failed: number | null | undefined): string {
  const parts: string[] = [];

  if (inProgress && inProgress > 0) {
    parts.push(`${versions(inProgress)} being rescanned`);
  }

  if (failed && failed > 0) {
    parts.push(`the last rescan of ${versions(failed)} failed`);
  }

  return parts.length ? `${capitalize(parts.join(' and '))}. Showing the last completed scans.` : '';
}

/**
 * Tooltip for a badge that rolls up several versions (a package or a repository) when some of them
 * have no completed scan yet, so their first scan is unfinished or failed and they are not part of
 * the severity. Empty when there is nothing to flag.
 */
export function unscannedCountsTitle(inProgress: number | null | undefined, failed: number | null | undefined): string {
  const parts: string[] = [];

  if (inProgress && inProgress > 0) {
    parts.push(`${versions(inProgress)} being scanned for the first time`);
  }

  if (failed && failed > 0) {
    parts.push(`the first scan of ${versions(failed)} failed`);
  }

  return parts.length ? `${capitalize(parts.join(' and '))}.` : '';
}

function versions(count: number): string {
  return count === 1 ? '1 version' : `${count} versions`;
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
