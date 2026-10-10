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

/** The longest reason shown. The backend already bounds it; this only guards against an older backend. */
export const MAX_FAILURE_REASON_LENGTH = 200;

/**
 * The reason a scan failed, ready to show: the first line only, trimmed and cut to
 * {@link MAX_FAILURE_REASON_LENGTH} characters. An empty string when there is nothing to show. The
 * text is rendered by interpolation (escaped), never as HTML.
 */
export function scanFailureReason(message: string | null | undefined): string {
  const firstLine = (message ?? '').trim().split(/\r?\n/, 1)[0].trim();

  return firstLine.length <= MAX_FAILURE_REASON_LENGTH
    ? firstLine
    : firstLine.slice(0, MAX_FAILURE_REASON_LENGTH - 3).trimEnd() + '...';
}
