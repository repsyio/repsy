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
import type { BrowserContext } from '@playwright/test';

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
}
