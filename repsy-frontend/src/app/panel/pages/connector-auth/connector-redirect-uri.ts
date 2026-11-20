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
 * Validates the `redirect_uri` of `/connector/auth` (RPS-2660) and returns it, or `null` when the page must not
 * send the browser there. The server compares it with the redirects the operator registered and refuses any other
 * one before a code exists; this check only keeps the page from showing a consent for an address that cannot be
 * registered: an absolute `https` URL (plain `http` on a loopback host, for tests) without user info or a fragment,
 * that the parser does not rewrite.
 */
export function parseConnectorRedirectUri(value: string | null | undefined): string | null {
  if (typeof value !== 'string' || value === '') {
    return null;
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  const schemeAllowed = url.protocol === 'https:' || (url.protocol === 'http:' && loopback);
  if (!schemeAllowed || url.username !== '' || url.password !== '' || url.hash !== '' || url.href !== value) {
    return null;
  }
  return url.href;
}

/**
 * The address the browser goes to once the user approved: the registered redirect with the one-time `code` and the
 * `state` of the application added to its query, which it may already have.
 */
export function connectorCallbackUrl(redirectUri: string, code: string, state: string | null): string {
  const url = new URL(redirectUri);
  url.searchParams.set('code', code);
  if (state !== null) {
    url.searchParams.set('state', state);
  }
  return url.href;
}
