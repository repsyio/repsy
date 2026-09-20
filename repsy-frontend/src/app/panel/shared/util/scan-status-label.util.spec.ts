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

describe('scanStatusLabel', () => {
  const labels: [ScanStatus, string][] = [
    [ScanStatus.Pending, 'Waiting...'],
    [ScanStatus.Queued, 'Queued...'],
    [ScanStatus.Running, 'Scanning...'],
    [ScanStatus.Completed, 'Completed'],
    [ScanStatus.Failed, 'Failed'],
  ];

  labels.forEach(([status, label]) => {
    it(`labels ${status} as "${label}"`, () => {
      expect(scanStatusLabel(status)).toBe(label);
    });
  });

  it('returns an empty string when there is no status', () => {
    expect(scanStatusLabel(null)).toBe('');
    expect(scanStatusLabel(undefined)).toBe('');
  });
});
