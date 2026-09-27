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
 * RPS-1617. Runtime errors of the panel used to be invisible: a component that threw but still rendered
 * passed every test. Every UI browser context is now watched (`watchPageErrors`), and the test fails at
 * teardown (`ui/fixtures.ts`) with everything that was recorded and not allowed:
 *
 *  - `pageerror`   an uncaught exception or unhandled promise rejection (Playwright's `weberror`);
 *  - `console`     a `console.error` (the panel's `AppGlobalErrorHandler` and `ErrorHandlerService` log
 *                  through it, and so does Chromium for a blocked resource);
 *  - `csp`         a `securitypolicyviolation` event, re-logged by an init script with a recognisable
 *                  prefix (Chromium's own "Refused to ..." console line is recorded too).
 *
 * Nothing here knows a URL or a route of the panel, so a UI project for another target reuses it as is.
 *
 * A spec that provokes errors on purpose says so with `allow(pattern, reason)` (from the `pageErrors`
 * fixture) or `test.use({ allowedPageErrors: errorToasts(reason, 'Toast text') })` for a whole `describe`. The reason is mandatory:
 * a ticket key for a bug that is still open, or the sentence that says why the error is by design.
 * Nothing is muted globally except `GLOBALLY_IGNORED`.
 */
import type { BrowserContext, ConsoleMessage } from '@playwright/test';

export type PageErrorKind = 'pageerror' | 'console' | 'csp';

export interface RecordedPageError {
  kind: PageErrorKind;
  text: string;
  /** The page URL at the time, for the failure message. */
  url: string;
}

/**
 * What `test.use({ allowedPageErrors })` takes. An object, not a bare array: Playwright reads a
 * two-element array given to `test.use` as its `[value, options]` fixture tuple.
 */
export interface PageErrorAllowList {
  entries: readonly AllowedPageError[];
}

export interface AllowedPageError {
  /** Matched against the text of the error (never against its kind or URL). */
  pattern: RegExp;
  /** Why this error is expected: an open ticket key, or why the spec provokes it. Required. */
  reason: string;
}

/** The prefix of the console line the init script writes for a `securitypolicyviolation` event. */
export const CSP_VIOLATION_PREFIX = '[csp-violation]';

/**
 * Console errors that are not runtime errors of the panel, ignored in every test:
 *  - Chromium logs "Failed to load resource: the server responded with a status of 404 ..." for every
 *    failed request, and a spec that stubs a 4xx/5xx or an abort, a not-found state, a refused
 *    login, or the flake defaults' blocked third-party hosts all produce them by design. The failure
 *    itself is asserted by the spec (a toast, a state); the panel's own handling of it is what
 *    `ErrorHandlerService` logs, which is not a runtime error either (see `HANDLED_API_ERROR`).
 *  - Firefox logs "downloadable font: download failed ... status=2152398850" (NS_BINDING_ABORTED) for a font
 *    whose download a navigation cancelled (RPS-1651). Anything a page's own `console.error` says from
 *    `beforeunload` on is dropped too, in `watchPageErrors` (WebKit's cancelled XHRs, see there).
 */
export const GLOBALLY_IGNORED: readonly AllowedPageError[] = [
  {
    pattern: /^Failed to load resource\b/,
    reason:
      'Chromium logs every 4xx/5xx/aborted request; the specs assert those outcomes themselves',
  },
  {
    // 2152398850 is NS_BINDING_ABORTED: the font's download was cancelled, here by a navigation (RPS-1651).
    pattern: /downloadable font: download failed .*status=2152398850/,
    reason:
      'Firefox logs a font whose download a navigation aborted (the Chromium case above, for Firefox)',
  },
];

const escapeRegExp = (text: string): string => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export class PageErrors {
  private readonly recorded: RecordedPageError[] = [];
  private readonly allowed: AllowedPageError[] = [...GLOBALLY_IGNORED];

  /** Allows errors whose text matches `pattern`. `reason` is required (ticket key or "by design: ..."). */
  allow(pattern: RegExp, reason: string): void {
    if (!reason.trim()) {
      throw new Error(
        `allowPageErrors(${pattern}) needs a reason: a ticket key or why it is by design`,
      );
    }
    this.allowed.push({ pattern, reason });
  }

  /** `allow()` for the error toast `message` (matched whole): see `errorToasts`. */
  allowToast(message: string, reason: string): void {
    this.allow(new RegExp(`^${escapeRegExp(message)}$`), reason);
  }

  record(error: RecordedPageError): void {
    this.recorded.push(error);
  }

  /** Everything recorded so far, allowed or not. */
  all(): readonly RecordedPageError[] {
    return this.recorded;
  }

  /** What was recorded and no `allow` entry covers. */
  unexpected(): RecordedPageError[] {
    return this.recorded.filter(
      (error) => !this.allowed.some((entry) => entry.pattern.test(error.text)),
    );
  }

  /** The message a failing test shows. */
  describeUnexpected(): string {
    const list = this.unexpected();
    return (
      `The panel raised ${list.length} runtime error${list.length === 1 ? '' : 's'} during this test ` +
      '(page errors, console errors, CSP violations). Fix the product, or, for an error the spec ' +
      'provokes on purpose, allow it with `pageErrors.allow(/.../, "reason or RPS-nnnn")` ' +
      '(src/ui/page-errors.ts):\n' +
      list.map((error) => `  - [${error.kind}] ${error.text}  (${error.url})`).join('\n')
    );
  }
}

function consoleText(message: ConsoleMessage): string {
  return message.text().trim();
}

function pageUrl(message: ConsoleMessage): string {
  return message.page()?.url() || message.location().url || '(no page)';
}

/**
 * Starts recording the runtime errors of every page of `context` into `errors`, and keeps recording
 * for pages opened later. Call it before the first navigation.
 */
export async function watchPageErrors(context: BrowserContext, errors: PageErrors): Promise<void> {
  context.on('weberror', (webError) => {
    const error = webError.error();
    errors.record({
      kind: 'pageerror',
      text: error.stack?.split('\n').slice(0, 4).join(' | ') || error.message,
      url: webError.page()?.url() || '(no page)',
    });
  });
  context.on('console', (message) => {
    if (message.type() !== 'error') {
      return;
    }
    const text = consoleText(message);
    errors.record({
      kind: text.startsWith(CSP_VIOLATION_PREFIX) ? 'csp' : 'console',
      text,
      url: pageUrl(message),
    });
  });
  await context.addInitScript((prefix) => {
    // WebKit fires the `error` event of every XHR a navigation cancels, in the document that is going away, and the
    // panel answers a status 0 with its "Connection error" toast and a console.error; Chromium and Firefox say nothing
    // (RPS-1651: PKG-*-01 goes to another page while the detail page's scan requests are in flight). Leaving the page is
    // not a runtime error of it, so nothing is logged from `beforeunload` on.
    let leaving = false;
    for (const event of ['beforeunload', 'pagehide']) {
      window.addEventListener(event, () => (leaving = true), true);
    }
    const consoleError = console.error.bind(console);
    console.error = (...args: unknown[]) => {
      if (!leaving) {
        consoleError(...args);
      }
    };
    window.addEventListener(
      'securitypolicyviolation',
      (event) => {
        console.error(
          `${prefix} ${event.effectiveDirective} blocked ${event.blockedURI || 'inline'} ` +
            `(${event.sourceFile || 'document'}:${event.lineNumber})`,
        );
      },
      true,
    );
  }, CSP_VIOLATION_PREFIX);
}

/**
 * Allow-list for the error TOASTS a spec provokes on purpose. The panel logs every error toast with
 * `console.error(message)` (`ToastService`), so a spec that stubs a 500, submits a duplicate name or
 * logs in with a wrong password sees that message in the console. Each entry matches the whole
 * message, so an unrelated toast in the same test is still a failure.
 */
export function errorToasts(reason: string, ...messages: string[]): PageErrorAllowList {
  return {
    entries: messages.map((message) => ({
      pattern: new RegExp(`^${escapeRegExp(message)}$`),
      reason,
    })),
  };
}

/** Joins allow-lists, for `test.use({ allowedPageErrors: allowLists(errorToasts(...), { entries: [...] }) })`. */
export function allowLists(...lists: PageErrorAllowList[]): PageErrorAllowList {
  return { entries: lists.flatMap((list) => list.entries) };
}
