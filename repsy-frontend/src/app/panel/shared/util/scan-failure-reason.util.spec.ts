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

import { MAX_FAILURE_REASON_LENGTH, scanFailureReason } from './scan-failure-reason.util';

describe('scanFailureReason', () => {
  it('is empty when there is no message', () => {
    expect(scanFailureReason(null)).toBe('');
    expect(scanFailureReason(undefined)).toBe('');
    expect(scanFailureReason('   \n ')).toBe('');
  });

  it('keeps a short message as it is', () => {
    expect(scanFailureReason('stub scanner: simulated scan failure')).toBe('stub scanner: simulated scan failure');
  });

  it('keeps the first line only', () => {
    expect(scanFailureReason('  boom\n\tat A.b(A.java:1)\r\nCaused by: x')).toBe('boom');
  });

  it('cuts a long message and marks the cut', () => {
    const reason = scanFailureReason('word '.repeat(100));

    expect(reason.length).toBeLessThanOrEqual(MAX_FAILURE_REASON_LENGTH);
    expect(reason.endsWith('...')).toBeTrue();
    expect(reason.startsWith('word word')).toBeTrue();
  });

  it('does not cut a message of exactly the limit', () => {
    const exact = 'x'.repeat(MAX_FAILURE_REASON_LENGTH);

    expect(scanFailureReason(exact)).toBe(exact);
  });
});
