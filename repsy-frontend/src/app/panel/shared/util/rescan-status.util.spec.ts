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
import {
  hasRescanFailed,
  isRescanInProgress,
  recentScanNote,
  rescanCountsTitle,
  rescanTitle,
  unscannedCountsTitle,
} from './rescan-status.util';

const IN_PROGRESS = [ScanStatus.Pending, ScanStatus.Queued, ScanStatus.Running];

describe('rescan status', () => {
  it('treats pending, queued and running as in progress', () => {
    for (const status of IN_PROGRESS) {
      expect(isRescanInProgress(status)).toBeTrue();
      expect(hasRescanFailed(status)).toBeFalse();
    }
  });

  it('treats failed as failed and not in progress', () => {
    expect(hasRescanFailed(ScanStatus.Failed)).toBeTrue();
    expect(isRescanInProgress(ScanStatus.Failed)).toBeFalse();
  });

  it('flags nothing for a completed or absent status', () => {
    for (const status of [ScanStatus.Completed, null, undefined]) {
      expect(isRescanInProgress(status)).toBeFalse();
      expect(hasRescanFailed(status)).toBeFalse();
      expect(rescanTitle(status)).toBe('');
      expect(recentScanNote(status, true)).toBe('');
      expect(recentScanNote(status, false)).toBe('');
    }
  });

  it('explains that a badge shows the last completed scan', () => {
    for (const status of IN_PROGRESS) {
      expect(rescanTitle(status)).toContain('Rescan in progress');
    }
    expect(rescanTitle(ScanStatus.Failed)).toContain('Last rescan failed');
  });

  it('calls an unfinished scan of a previously scanned version a rescan', () => {
    for (const status of IN_PROGRESS) {
      expect(recentScanNote(status, true)).toBe('Rescanning...');
    }
    expect(recentScanNote(ScanStatus.Failed, true)).toBe('Last rescan failed');
  });

  it('calls an unfinished scan of a never completed version by its plain status', () => {
    expect(recentScanNote(ScanStatus.Pending, false)).toBe('Waiting...');
    expect(recentScanNote(ScanStatus.Queued, false)).toBe('Queued...');
    expect(recentScanNote(ScanStatus.Running, false)).toBe('Scanning...');
    expect(recentScanNote(ScanStatus.Failed, false)).toBe('Failed');
  });

  describe('rescanCountsTitle', () => {
    it('flags nothing when no version is being rescanned or failed', () => {
      expect(rescanCountsTitle(0, 0)).toBe('');
      expect(rescanCountsTitle(null, undefined)).toBe('');
    });

    it('names the versions being rescanned', () => {
      expect(rescanCountsTitle(1, 0)).toBe('1 version being rescanned. Showing the last completed scans.');
      expect(rescanCountsTitle(3, null)).toBe('3 versions being rescanned. Showing the last completed scans.');
    });

    it('names the versions whose last rescan failed', () => {
      expect(rescanCountsTitle(0, 1)).toBe('The last rescan of 1 version failed. Showing the last completed scans.');
      expect(rescanCountsTitle(undefined, 2)).toBe(
        'The last rescan of 2 versions failed. Showing the last completed scans.',
      );
    });

    it('names both when some are rescanned and some failed', () => {
      expect(rescanCountsTitle(2, 1)).toBe(
        '2 versions being rescanned and the last rescan of 1 version failed. Showing the last completed scans.',
      );
    });
  });

  describe('unscannedCountsTitle', () => {
    it('flags nothing when every version has a completed scan', () => {
      expect(unscannedCountsTitle(0, 0)).toBe('');
      expect(unscannedCountsTitle(null, undefined)).toBe('');
    });

    it('names the versions being scanned for the first time', () => {
      expect(unscannedCountsTitle(1, 0)).toBe('1 version being scanned for the first time.');
      expect(unscannedCountsTitle(3, null)).toBe('3 versions being scanned for the first time.');
    });

    it('names the versions whose first scan failed', () => {
      expect(unscannedCountsTitle(0, 1)).toBe('The first scan of 1 version failed.');
      expect(unscannedCountsTitle(undefined, 2)).toBe('The first scan of 2 versions failed.');
    });

    it('names both when some are being scanned and some failed', () => {
      expect(unscannedCountsTitle(2, 1)).toBe(
        '2 versions being scanned for the first time and the first scan of 1 version failed.',
      );
    });
  });
});
