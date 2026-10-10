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

import { HttpErrorResponse } from '@angular/common/http';

/** What a version detail page says when its load failed for any reason other than "no such version". */
export const VERSION_LOAD_FAILED = 'The version could not be loaded';

/**
 * The message of a version detail page whose load failed (RPS-1625): a 404 means the version (or its
 * package) does not exist, which is what a link to a deleted or mistyped version leads to; anything else
 * is a failed load. The error interceptor has already toasted the failure, so this is the text the page
 * keeps in place of the detail, never an empty detail.
 */
export function versionLoadError(err: unknown, versionName: string): string {
  if (err instanceof HttpErrorResponse && err.status === 404) {
    return `Version '${versionName}' not found`;
  }
  return VERSION_LOAD_FAILED;
}
