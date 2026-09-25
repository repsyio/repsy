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
import type { APIResponse, BrowserContext, Route } from '@playwright/test';

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
 *    base URLs). The panel itself asks for no such host any more (RPS-1402, pinned by
 *    `no-third-party-requests.spec.ts`); this blocks anything added later, so runs are offline-safe
 *    and free of third-party jitter.
 *
 * The route is a URL predicate, so same-origin traffic is never intercepted, and a test's own
 * `page.route()` mock (page routes win over context routes) keeps working.
 *
 * Reads of the panel itself (its scripts, styles and API GETs) are fetched by Playwright instead of
 * Chromium, see `serveReadsFromNode` (RPS-1303).
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

  await serveReadsFromNode(context, allowed);
}

/** The requests whose loss leaves the panel unbooted or a view empty. Images and fonts are not among them. */
const READ_RESOURCE_TYPES: ReadonlySet<string> = new Set(['script', 'stylesheet', 'xhr', 'fetch']);

export interface ServeReadsOptions {
  /** How a read is fetched; `route.fetch()` (Playwright's own HTTP client) unless a test replaces it. */
  fetch?: (route: Route) => Promise<APIResponse>;
  /** Called with the URL of every read this route served, for the harness proof. */
  onServed?: (url: string) => void;
}

/**
 * RPS-1303. Fetches the panel's own reads (`GET` of a script, a stylesheet, or an `xhr`/`fetch` call to
 * an allowed origin) with Playwright's HTTP client (`route.fetch()`, Node) and hands the answer to the
 * page, instead of letting Chromium's network stack do it.
 *
 * Why: the `ui` runner shares the host's network namespace (`network_mode: host`, so that
 * `localhost:8080` reaches the stack), and Chromium fails every in-flight request with
 * `net::ERR_NETWORK_CHANGED` whenever that namespace's addresses or links change: a wifi interface
 * refreshing its IPv6 lifetimes, or any other container (a second stack, a Testcontainers run) starting
 * or stopping and its veth/bridge coming up, a burst of events over several seconds. Measured on a
 * failed run: `main-*.js`, `polyfills-*.js` and six chunks of the SPA failed with it while the document
 * itself was a 200, so the panel never booted and the test then waited 10 s for `pkg-toolbar` /
 * `settings-page` / `user-title` of a blank page. Node's sockets do not care about the host's
 * interfaces, so a read served from here cannot be lost that way. Nothing else changes: the page sees
 * the same request events, statuses, headers and bodies, and a test's own `page.route()` still wins
 * (it runs first, and `route.fallback()` from it ends up here).
 *
 * Left to Chromium on purpose: navigations (a refused `page.goto()` reports the error to its caller),
 * writes (never replayed), images and fonts (losing one costs the panel nothing), and a read whose
 * Playwright fetch itself fails (it falls back, so this can only remove a failure, never add one).
 */
export async function serveReadsFromNode(
  context: BrowserContext,
  allowedOrigins: ReadonlySet<string>,
  options: ServeReadsOptions = {},
): Promise<void> {
  const fetchRead = options.fetch ?? ((route: Route) => route.fetch());
  await context.route(
    (url) =>
      (url.protocol === 'http:' || url.protocol === 'https:') && allowedOrigins.has(url.origin),
    async (route) => {
      const request = route.request();
      if (request.method() !== 'GET' || !READ_RESOURCE_TYPES.has(request.resourceType())) {
        await route.fallback();
        return;
      }
      let response: APIResponse;
      try {
        response = await fetchRead(route);
      } catch {
        await route.fallback().catch(() => undefined);
        return;
      }
      options.onServed?.(request.url());
      await route.fulfill({ response }).catch(() => undefined);
    },
  );
}
