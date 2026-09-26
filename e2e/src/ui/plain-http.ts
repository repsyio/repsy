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
 * A browser context that behaves like a panel opened over plain HTTP on a LAN address
 * (`http://192.168.1.10:8080`), where `navigator.clipboard` does not exist (RPS-1623). The harness itself
 * always runs on `localhost`, a secure context with the clipboard permission granted, so its copy buttons
 * never met that case.
 *
 * `simulatePlainHttp(context)` installs an init script, before any document of the context loads, that
 *  - makes `navigator.clipboard` `undefined` (the API is absent there, not denied), and
 *  - wraps `document.execCommand('copy')`, the fallback the panel uses, to record what it copied (the
 *    selected text of the hidden textarea) and what the real browser answered, in `window.__copies`.
 *
 * `execResult: 'fail'` makes the wrapper answer `false` without copying, for "no copy method works".
 */
import type { BrowserContext, Page } from '@playwright/test';

export interface RecordedCopy {
  /** The text that was selected when `execCommand('copy')` ran. */
  text: string;
  /** What `execCommand` answered (the real browser's answer unless `execResult: 'fail'`). */
  ok: boolean;
}

export async function simulatePlainHttp(
  context: BrowserContext,
  options: { execResult?: 'browser' | 'fail' } = {},
): Promise<void> {
  await context.addInitScript(
    ({ fail }) => {
      const w = window as unknown as { __copies: { text: string; ok: boolean }[] };
      w.__copies = [];
      Object.defineProperty(Navigator.prototype, 'clipboard', {
        configurable: true,
        get: () => undefined,
      });
      const original = Document.prototype.execCommand;
      Document.prototype.execCommand = function (this: Document, command: string, ...rest) {
        if (command !== 'copy') {
          return original.call(this, command, ...(rest as [boolean?, string?]));
        }
        const active = this.activeElement as HTMLTextAreaElement | null;
        const text = active && 'value' in active ? active.value : String(this.getSelection());
        const ok = fail ? false : original.call(this, command);
        w.__copies.push({ text, ok });
        return ok;
      };
    },
    { fail: options.execResult === 'fail' },
  );
}

/** Every `execCommand('copy')` the page made since it loaded. */
export async function recordedCopies(page: Page): Promise<RecordedCopy[]> {
  return page.evaluate(() => (window as unknown as { __copies: RecordedCopy[] }).__copies ?? []);
}
