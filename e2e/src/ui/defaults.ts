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
 * Flake defaults applied to every browser context of the UI suite (`applyUiDefaults`). The panel
 * animates almost everything (`animate.enter` on ~200 elements, a 400 ms toast slide-in) and loads a
 * handful of third-party resources; neither should decide whether a test passes.
 */
import type { BrowserContext, Page, Request } from '@playwright/test';

/**
 * Near-zero motion, deliberately not `animation: none`: Angular's `animate.enter`/`animate.leave`
 * wait for the `animationend` event, which never fires for `animation: none`, and would leave a
 * leaving element in the DOM. The app itself ignores `prefers-reduced-motion`, so this stylesheet
 * (not `reducedMotion: 'reduce'`) is what actually removes the motion.
 */
export const NO_MOTION_CSS =
  '*,*::before,*::after{animation-duration:1ms!important;animation-delay:0s!important;' +
  'transition-duration:0s!important;transition-delay:0s!important;scroll-behavior:auto!important}';

const STYLE_ELEMENT_ID = 'repsy-e2e-no-motion';

/**
 * Applies the flake defaults to `context`:
 *  - `NO_MOTION_CSS`, injected into every document as soon as `<head>` exists (an init script, so it
 *    survives reloads and full navigations such as the header's raw "Profile" link);
 *  - an abort for every http(s) request whose origin is not in `allowedOrigins` (the UI, API and repo
 *    base URLs). That blocks Google Tag Manager, gtag, the Font Awesome CDN and Gravatar today and
 *    anything added later, so runs are offline-safe and free of third-party jitter. The avatar
 *    component falls back to the user's initial when Gravatar fails.
 *
 * The route is a URL predicate, so same-origin traffic is never intercepted, and a test's own
 * `page.route()` mock (page routes win over context routes) keeps working.
 *
 * It also installs `healHostNetworkChange` (RPS-1303, below).
 */
export async function applyUiDefaults(
  context: BrowserContext,
  allowedOrigins: readonly string[],
): Promise<void> {
  await context.addInitScript(
    ({ css, id }) => {
      const install = (): boolean => {
        if (document.getElementById(id)) {
          return true;
        }
        if (!document.head) {
          return false;
        }
        const style = document.createElement('style');
        style.id = id;
        style.textContent = css;
        document.head.appendChild(style);
        return true;
      };
      if (!install()) {
        const observer = new MutationObserver(() => {
          if (install()) {
            observer.disconnect();
          }
        });
        observer.observe(document, { childList: true, subtree: true });
      }
    },
    { css: NO_MOTION_CSS, id: STYLE_ELEMENT_ID },
  );

  const allowed = new Set(allowedOrigins);
  await context.route(
    (url) => (url.protocol === 'http:' || url.protocol === 'https:') && !allowed.has(url.origin),
    (route) => route.abort('blockedbyclient'),
  );

  healHostNetworkChange(context, allowed);
}

/** What Chromium reports for a request it aborted because the HOST's network configuration changed. */
export const HOST_NETWORK_CHANGED = /ERR_NETWORK_CHANGED/;

/** How many reloads `healHostNetworkChange` gives one page before the failure is left to the test. */
export const MAX_NETWORK_CHANGE_RELOADS = 5;

/**
 * A request of the load being replaced that fails AFTER its reload started is a straggler of the same
 * instant (Chromium fails everything in flight at once and Playwright delivers the events within a few
 * milliseconds). One that fails later than this belongs to the reload itself: the network changed again.
 */
const RELOAD_STRAGGLER_MS = 100;

/**
 * RPS-1303. The `ui` runner shares the host's network namespace (`network_mode: host`, so that
 * `localhost:8080` reaches the stack), and Chromium aborts every in-flight request with
 * `net::ERR_NETWORK_CHANGED` whenever that namespace's addresses or links change: a wifi interface
 * refreshing its IPv6 lifetimes, or any other container (a second stack, a Testcontainers run) starting
 * or stopping and its veth/bridge coming up, which is a burst of events over several seconds. Measured:
 * the trace of a failed run shows `main-*.js`, `polyfills-*.js` and six chunks of the SPA failing with it
 * while the document itself was a 200, so the panel never booted and the test then waited 10 s for
 * `pkg-toolbar` / `settings-page` / `user-title` of a blank page. Nothing in the panel or the test is
 * wrong: a reload gets the page.
 *
 * So a same-origin GET (the SPA's scripts and styles, or a panel-API read that the view renders from)
 * that fails with exactly that error reloads its page, and reloads again when that load is hit too, up
 * to `MAX_NETWORK_CHANGE_RELOADS` times. A write (POST/PUT/DELETE) is never replayed: its outcome is
 * unknown. Top-level navigations are left alone, since a `page.goto()` that is refused reports the error
 * to its caller itself. Only this one error text matches, so a test that aborts a request on purpose
 * (`route.abort()`) is unaffected. `matches` exists for the harness proof, which has no way to make
 * Chromium report the real error.
 */
export function healHostNetworkChange(
  context: BrowserContext,
  allowedOrigins: ReadonlySet<string>,
  matches: RegExp = HOST_NETWORK_CHANGED,
): void {
  const reloads = new WeakMap<Page, number>();
  const startedAt = new WeakMap<Page, number>();
  const running = new WeakSet<Page>();
  const again = new WeakSet<Page>();

  const heal = async (page: Page, failedUrl: string): Promise<void> => {
    running.add(page);
    try {
      do {
        again.delete(page);
        const done = (reloads.get(page) ?? 0) + 1;
        reloads.set(page, done);
        startedAt.set(page, Date.now());
        console.warn(
          `[ui] host network changed (${failedUrl}): reloading ${page.url()} (${done}/${MAX_NETWORK_CHANGE_RELOADS})`,
        );
        await page.reload().catch(() => undefined);
      } while (
        again.has(page) &&
        !page.isClosed() &&
        (reloads.get(page) ?? 0) < MAX_NETWORK_CHANGE_RELOADS
      );
    } finally {
      running.delete(page);
      again.delete(page);
    }
  };

  context.on('requestfailed', (request: Request) => {
    if (
      request.method() !== 'GET' ||
      request.isNavigationRequest() ||
      !matches.test(request.failure()?.errorText ?? '') ||
      !allowedOrigins.has(new URL(request.url()).origin)
    ) {
      return;
    }
    const page = request.frame()?.page();
    if (!page || page.isClosed() || (reloads.get(page) ?? 0) >= MAX_NETWORK_CHANGE_RELOADS) {
      return;
    }
    if (running.has(page)) {
      if (Date.now() - (startedAt.get(page) ?? 0) > RELOAD_STRAGGLER_MS) {
        again.add(page);
      }
      return;
    }
    void heal(page, request.url());
  });
}
