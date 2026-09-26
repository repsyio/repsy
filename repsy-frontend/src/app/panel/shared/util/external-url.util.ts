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
 * The URL to link to for a value a package publisher wrote (a homepage, a project URL), or `null` when it
 * must not become a link (RPS-1623).
 *
 * Angular's URL sanitiser rewrites `javascript:` only, so a `data:text/html` or `vbscript:` homepage would
 * still be a live link. Only http(s) is linked. The value is parsed with the browser's own URL parser, which
 * ignores surrounding whitespace and control characters, reads a backslash as a slash and matches the scheme
 * case-insensitively, so an obfuscated `  JaVaScRiPt:` cannot pass as http(s).
 */
export function externalHttpUrl(raw: string | null | undefined): string | null {
  const candidate = raw?.trim();
  if (!candidate) {
    return null;
  }
  try {
    const { protocol } = new URL(candidate);
    return protocol === 'http:' || protocol === 'https:' ? candidate : null;
  } catch {
    return null;
  }
}
