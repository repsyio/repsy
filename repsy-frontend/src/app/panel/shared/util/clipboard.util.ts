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
 * Copies text to the clipboard and reports whether it worked. It never throws.
 *
 * `navigator.clipboard` exists only in a secure context (HTTPS or localhost) and can be denied by a
 * permissions policy, so a panel opened over plain HTTP, inside an iframe without the permission or in
 * an older browser has none, or a `writeText` that rejects. The legacy `document.execCommand('copy')`
 * on a hidden textarea still works there, so it is the fallback.
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Denied or unavailable: fall through to the legacy path.
    }
  }

  return copyWithExecCommand(text);
}

function copyWithExecCommand(text: string): boolean {
  if (typeof document === 'undefined' || !document.body) {
    return false;
  }

  const activeElement = document.activeElement as HTMLElement | null;
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.setAttribute('aria-hidden', 'true');
  textarea.style.position = 'fixed';
  textarea.style.top = '0';
  textarea.style.left = '-9999px';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);

  try {
    textarea.select();
    textarea.setSelectionRange(0, text.length);
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    document.body.removeChild(textarea);
    activeElement?.focus?.();
  }
}
