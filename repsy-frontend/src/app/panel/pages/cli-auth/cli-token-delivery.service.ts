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

import { Injectable } from '@angular/core';

/** The form fields the CLI reads from the POST body (`application/x-www-form-urlencoded`). */
export const CALLBACK_TOKEN_FIELD = 'token';
export const CALLBACK_STATE_FIELD = 'state';

/** How long to wait for the browser's report of a refused post (it arrives within a few milliseconds). */
const VIOLATION_WAIT_MS = 300;

@Injectable({ providedIn: 'root' })
export class CliTokenDeliveryService {
  /**
   * Posts the new token to the CLI's loopback address (`redirect_uri`, already checked by `parseLoopbackRedirectUri`) as the
   * body of a form, in a NEW tab. Why a form and a new tab:
   *
   * - a form body never reaches the address bar, the history or a `Referer` (`noreferrer`), which a URL parameter or a
   *   fragment would;
   * - a plain form post is a top-level navigation, so it needs no CORS answer from the CLI, no `connect-src`, and it is not
   *   a "local network" sub-request (`fetch`/XHR to a loopback address is what the browsers gate);
   * - the post cannot tell the page whether the CLI listened. In the new tab the person sees the CLI's own answer or the
   *   browser's error page, and this tab keeps the token in memory so the page can still offer the paste path.
   *
   * The one failure the page can see is the browser refusing to send the form at all: a `form-action` Content Security
   * Policy that does not name the address (the IPv6 loopback cannot be named in one). The browser reports that as a
   * `securitypolicyviolation` event, and the result says so (`false`). `state` is sent as it came in; it is left out only
   * when the link had none.
   *
   * @returns `false` when the browser's Content Security Policy blocked the post, else `true` (sent, not confirmed).
   */
  async postToken(redirectUri: string, token: string, state: string | null): Promise<boolean> {
    let blocked = false;
    const onViolation = (event: SecurityPolicyViolationEvent): void => {
      if (event.violatedDirective.startsWith('form-action')) {
        blocked = true;
      }
    };
    document.addEventListener('securitypolicyviolation', onViolation);
    try {
      this.submit(redirectUri, token, state);
      // The browser checks the policy and reports a refusal a moment after the submit, not in it.
      await new Promise<void>((resolve) => setTimeout(resolve, VIOLATION_WAIT_MS));
    } finally {
      document.removeEventListener('securitypolicyviolation', onViolation);
    }
    return !blocked;
  }

  private submit(redirectUri: string, token: string, state: string | null): void {
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = redirectUri;
    form.target = '_blank';
    form.setAttribute('rel', 'noopener noreferrer');
    form.enctype = 'application/x-www-form-urlencoded';
    form.hidden = true;
    const fields: [string, string][] = [[CALLBACK_TOKEN_FIELD, token]];
    if (state !== null) {
      fields.push([CALLBACK_STATE_FIELD, state]);
    }
    for (const [name, value] of fields) {
      const input = document.createElement('input');
      input.type = 'hidden';
      input.name = name;
      input.value = value;
      form.appendChild(input);
    }
    document.body.appendChild(form);
    try {
      form.submit();
    } finally {
      // The secret does not stay in the document longer than the submit.
      form.remove();
    }
  }
}
