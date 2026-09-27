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
 * Reading back what a copy button copied, in every browser of the UI projects (RPS-1651).
 *
 * Chromium lets a test grant `clipboard-read` and `clipboard-write` and then `navigator.clipboard.readText()`
 * returns what the panel put on the system clipboard. Firefox and WebKit have neither permission (Playwright
 * throws "Unknown permission" on `grantPermissions`), and their `readText()` needs a paste prompt a headless
 * test cannot answer. So there the context records what the page asked `navigator.clipboard.writeText()` to
 * write (the panel's copy buttons call exactly that on a secure context, `localhost` or https), and
 * `copiedText()` returns the last recorded value. That proves the button copied the right text; only the
 * Chromium run also proves the text reached the clipboard.
 *
 * `allowClipboard(context)` before the first navigation of the context (it installs an init script), then
 * click the copy button, then `copiedText(page)`.
 */
import type { BrowserContext, Page } from '@playwright/test';

interface ClipboardWindow {
  __clipboardWrites?: string[];
}

function isChromium(context: BrowserContext): boolean {
  return context.browser()?.browserType().name() === 'chromium';
}

/** Grants the clipboard permissions where the browser has them and records `writeText` calls everywhere. */
export async function allowClipboard(context: BrowserContext): Promise<void> {
  if (isChromium(context)) {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    return;
  }
  await context.addInitScript(() => {
    const w = window as unknown as ClipboardWindow;
    w.__clipboardWrites = [];
    const clipboard = navigator.clipboard as Clipboard | undefined;
    if (!clipboard) {
      return;
    }
    const original = clipboard.writeText.bind(clipboard);
    clipboard.writeText = (text: string) => {
      w.__clipboardWrites?.push(text);
      return original(text);
    };
  });
}

/** The text of the last copy: the system clipboard in Chromium, the last `writeText` argument elsewhere. */
export async function copiedText(page: Page): Promise<string> {
  if (isChromium(page.context())) {
    return page.evaluate(() => navigator.clipboard.readText());
  }
  const writes = await page.evaluate(
    () => (window as unknown as ClipboardWindow).__clipboardWrites ?? [],
  );
  if (writes.length === 0) {
    throw new Error('no navigator.clipboard.writeText call was recorded: did the copy button run?');
  }
  return writes[writes.length - 1];
}
