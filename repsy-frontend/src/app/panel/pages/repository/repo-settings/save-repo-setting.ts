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

import { Observable } from 'rxjs';

export interface SettingSaveOutcome {
  /** The PUT succeeded: refresh the page's settings and say so. */
  saved: () => void;
  /** The PUT failed: put the control back to the value the repository really has. */
  failed: () => void;
  /** The PUT ended, either way: unlock the control. */
  settled: () => void;
}

/**
 * The one way a settings toggle saves (RPS-1618). The caller sends a body holding only the field(s) it owns (an
 * omitted field keeps its stored value, `RepoSettingsForm` in the OpenAPI spec), so a save cannot undo a change that
 * another toggle or another admin made meanwhile (RPS-1619). The control was flipped optimistically, so `failed` has
 * to flip it back: the shown value must always be the stored one. The error toast is the HTTP interceptor's.
 */
export function saveRepoSetting(request: Observable<unknown>, outcome: SettingSaveOutcome): void {
  request.subscribe({
    next: () => {
      outcome.settled();
      outcome.saved();
    },
    error: () => {
      outcome.settled();
      outcome.failed();
    },
  });
}
