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

/**
 * The one way a UI spec answers a request with a body it made up (RPS-1652, H9): `fulfillJson<Model>(...)`.
 *
 * A stub body is data the panel parses; when the panel API changes (a field renamed, retyped, made
 * required) a stub typed as `unknown` or written as a string keeps "working" and the test fails at run time
 * with a screen that shows nothing, or worse passes on an answer the backend no longer gives. So the body is
 * a value of a model generated from `openapi-spec.yaml` (`stub-models.ts`) and `tsc` breaks on the drift.
 *
 * The model is a type argument the caller MUST write: `fulfillJson<ErrorResponse>(route, 401, {...})`. The
 * default `never` (with `NoInfer`, so the body cannot supply the type) makes a call without one a compile
 * error, instead of a body that is checked against whatever it happens to be. ESLint (`eslint.config.js`)
 * forbids the other spellings, `route.fulfill({ body })` and `route.fulfill({ json })`, outside this file;
 * a body that is not JSON on purpose (a proxy's HTML error page) goes through `fulfillText`.
 */
import { ERROR_CODES } from '../error-codes.js';
import type { Route } from '@playwright/test';

import type { ProblemDetail } from './stub-models.js';

/**
 * The body of a failed panel request (RFC 9457 `application/problem+json`), the spec's `ProblemDetail`. `code` is a
 * plain string here, not the spec's code enum: a stub may answer a code the server never emits to prove the panel
 * copes with an unknown one.
 */
export type ErrorResponse = Omit<ProblemDetail, 'code'> & { code: string };

/** Answers `route` with `status` and `body` as JSON. `Model` is the generated model the body is an instance of. */
export async function fulfillJson<Model = never>(
  route: Route,
  status: number,
  body: NoInfer<Model>,
  headers: Record<string, string> = {},
): Promise<void> {
  await route.fulfill({
    status,
    headers,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/** Answers `route` with `status` and a body that is deliberately not a model: text a client must survive. */
export async function fulfillText(
  route: Route,
  status: number,
  text: string,
  contentType = 'text/plain',
  headers: Record<string, string> = {},
): Promise<void> {
  await route.fulfill({ status, headers, contentType, body: text });
}

/**
 * The problem document the backend sends with every failed panel request (`ProblemDetail`). What the panel reads is
 * `code` (which message it shows) and `detail` (the message itself); `status` and `code` are filled in when a stub
 * leaves them out.
 */
export function errorBody(fields: Partial<ErrorResponse> = {}): ErrorResponse {
  return { status: 500, code: ERROR_CODES.ERROR_OCCURRED, ...fields };
}
