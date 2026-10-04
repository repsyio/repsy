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

/** One entry of `errors` of a validation problem. */
export interface ProblemField {
  field: string;
  code: string;
  message?: string;
}

/**
 * The body of a failed panel request: an RFC 9457 `application/problem+json` document. `code` is the stable key of the
 * failure (translations are keyed by it), `detail` its message, `traceId` the unique id of the failure and `errors`
 * the fields a validation failure is about.
 */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  traceId?: string;
  errors?: ProblemField[];
}

/**
 * Angular sets `HttpErrorResponse.error` to `null` when the server answers without a body, and to a plain string when
 * the body is not JSON (for example a `502 Bad Gateway` page from a proxy). Only an object can be a problem.
 */
export function problemOf(res: HttpErrorResponse | null | undefined): Problem | null {
  const body: unknown = res?.error;

  return typeof body === 'object' && body !== null ? (body as Problem) : null;
}

export function problemCode(res: HttpErrorResponse | null | undefined): string | undefined {
  return problemOf(res)?.code;
}

export function problemDetail(res: HttpErrorResponse | null | undefined): string | undefined {
  return problemOf(res)?.detail;
}
