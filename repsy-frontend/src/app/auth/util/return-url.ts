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
 * Query parameter under which `AuthGuard` remembers the URL an anonymous visitor asked for, so the
 * login form can send them back there (RPS-1278).
 */
export const RETURN_URL_PARAM = 'returnUrl';

/**
 * Returns `value` when it is a safe in-app destination for the post-login redirect, else `null`.
 *
 * The value comes from the address bar, so it is attacker-controlled and must never leave the
 * app: only an absolute path is accepted, so a scheme (`https://evil`, `javascript:`), a
 * protocol-relative URL (`//evil`) and the backslash forms browsers treat like `//` are refused,
 * as is any control character (browsers strip tabs and newlines inside a URL, which would turn
 * `/<TAB>/evil` into `//evil`).
 */
export function safeReturnUrl(value: unknown): string | null {
  if (typeof value !== 'string' || !/^\/(?![/\\])/.test(value) || /[\p{Cc}\\]/u.test(value)) {
    return null;
  }
  try {
    const base = 'http://return-url.invalid';
    return new URL(value, base).origin === base ? value : null;
  } catch {
    return null;
  }
}
