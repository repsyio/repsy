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

import { Observable, timer } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';

/**
 * Repeatedly calls {@code fetchFn} every {@code intervalMs} (first call immediately) and completes
 * the returned observable right after {@code isTerminal} first returns {@code true} for a result —
 * the caller never has to manage its own interval/stop condition, just subscribe and unsubscribe on
 * teardown. {@code switchMap} guarantees at most one in-flight request at a time: if a fetch is still
 * pending when the next tick fires, that tick is dropped rather than piling up a second request.
 */
export function pollUntilTerminal<T>(
  fetchFn: () => Observable<T>,
  isTerminal: (result: T) => boolean,
  intervalMs = 3000,
): Observable<T> {
  return timer(0, intervalMs).pipe(
    switchMap(() => fetchFn()),
    takeWhile((result) => !isTerminal(result), true),
  );
}
