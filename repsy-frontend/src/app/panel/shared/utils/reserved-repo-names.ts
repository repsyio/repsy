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

import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * Repository names that would collide with a fixed top-level route declared ahead of the
 * `:repoName` route in `app.routes.ts` (`login`, `profile`, `repositories`, `users`, `security`,
 * `not-found`), plus the paths the API port forwards to the SPA before anything more specific can
 * claim them (`api`, `assets`, `favicon.ico`), plus the literal segments the API keeps directly
 * under `/api/repos/` next to its `{repoName}` variable (`counts`, `security-summary`). Compared
 * case-insensitively.
 *
 * Keep this in sync with `app.routes.ts` (which points back here) and with the backend's mirror,
 * `io.repsy.os.shared.repo.utils.RepoUtils.RESERVED_REPO_NAMES`.
 */
export const RESERVED_REPO_NAMES: ReadonlySet<string> = new Set([
  'login',
  'profile',
  'repositories',
  'users',
  'security',
  'not-found',
  'api',
  'assets',
  'favicon.ico',
  'counts',
  'security-summary',
]);

export function isReservedRepoName(name: string | null | undefined): boolean {
  return !!name && RESERVED_REPO_NAMES.has(name.toLowerCase());
}

/** Angular validator flagging a reserved repository name with a `reservedName` error. */
export function reservedRepoNameValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null =>
    isReservedRepoName(control.value) ? { reservedName: true } : null;
}
