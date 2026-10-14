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

/**
 * The shape of an address the CLI may ask a token to be sent to (RPS-2039): `http`, the IPv4 or IPv6 loopback
 * literal, a port (1 to 65535, no leading zero), then a path that starts with a single "/". Nothing else is
 * allowed in front of the path: no user info, no other host, no `localhost`, no short or hex IPv4 forms.
 * The path itself may not hold whitespace, control characters, a backslash or a "#".
 */
const LOOPBACK_URI = /^http:\/\/(127\.0\.0\.1|\[::1\]):([1-9]\d{0,4})(\/[^\s\p{Cc}\\#]*)$/u;

/** An encoded slash, backslash or NUL in the path: a server may decode these into a different path. */
const ENCODED_PATH_TRICKS = /%(?:2f|5c|00)/i;

/**
 * Validates the `redirect_uri` of `/cli/auth` and returns the address to post the token to, or `null` when it is not an
 * address of the machine the browser runs on. This is the open-redirect guard: the page hands a secret to this address,
 * so a doubtful value is refused.
 *
 * The value is checked in two steps that must both agree. First the whole string has to match {@link LOOPBACK_URI}, so
 * a host that merely starts like a loopback address (`127.0.0.1.evil.com`, `127.0.0.1@evil.com`) never matches. Then it
 * is parsed with `URL` (the way the browser will read it), and the parsed host, port, user info and fragment are checked
 * again, and the parsed `href` has to equal the input, so a value the parser rewrites (dot segments, other
 * spellings) is refused instead of silently pointing somewhere else. The comparison is never a prefix check.
 */
export function parseLoopbackRedirectUri(value: string | null | undefined): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const match = LOOPBACK_URI.exec(value);
  if (!match) {
    return null;
  }
  const port = Number(match[2]);
  const path = match[3];
  if (port < 1 || port > 65535 || path.startsWith('//') || ENCODED_PATH_TRICKS.test(path)) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  const hostIsLoopback = url.hostname === '127.0.0.1' || url.hostname === '[::1]';
  if (
    url.protocol !== 'http:' ||
    !hostIsLoopback ||
    url.port !== match[2] ||
    url.username !== '' ||
    url.password !== '' ||
    url.hash !== '' ||
    !url.pathname.startsWith('/') ||
    url.href !== value
  ) {
    return null;
  }
  return url.href;
}
