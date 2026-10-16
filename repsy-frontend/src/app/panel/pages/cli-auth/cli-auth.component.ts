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
import { NgTemplateOutlet } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { AccessTokenCreated, AccessTokensApi, AccessTokenScope } from '../../../../generated/api';
import { AuthService } from '../../../auth/pages/services/auth.service';
import { CopyClipboardComponent } from '../../shared/components/copy-clipboard/copy-clipboard.component';
import { DEFAULT_CLI_SCOPES } from '../settings/access-tokens/access-token-limits';
import {
  parseRequestedScopes,
  SELECTABLE_SCOPES,
  SelectableScope,
} from '../settings/access-tokens/access-token-scopes';
import { CliTokenDeliveryService } from './cli-token-delivery.service';
import { parseLoopbackRedirectUri } from './loopback-redirect-uri';

/**
 * What the page is doing:
 *
 * - `approve`: the authorize screen. Nothing is created until "Authorize". With a valid loopback `redirect_uri` the
 *   token is posted to the CLI; without one it is shown on screen to paste.
 * - `delivered`: authorized; the token was created and posted to the CLI.
 * - `pasted`: authorized without a `redirect_uri`; the token is on screen to copy by hand.
 * - `declined`: the person said no. Nothing was created.
 * - `refused`: the link has a `redirect_uri` that is not a loopback address. Nothing was created.
 */
export type CliAuthMode = 'approve' | 'delivered' | 'pasted' | 'declined' | 'refused';

/** The temporary token of a CLI login lives this long, fixed by the server (shown on the screen, never asked for). */
export const CLI_TOKEN_LIFETIME_HOURS = 24;

/**
 * `/cli/auth?state=&redirect_uri=&scopes=` (RPS-1905, RPS-2039): the page the CLI opens in a browser.
 *
 * It is an authorize screen: it shows the account, the scopes in plain words and the 24-hour validity, and only on
 * "Authorize" asks the server for a temporary CLI token. The token is not a personal access token: it is not listed
 * under Settings, does not count against the 50 token limit, expires on its own and replaces the previous one of the
 * same user. With a loopback `redirect_uri` the secret is POSTed in a form body to the CLI, with `state` unchanged
 * (never in a URL); without one it is shown on screen to paste. The previous token is revoked only after the new one
 * exists and the browser has not refused to send it on. The server keeps no `state`. A scope the page does not know
 * is dropped, so the screen always shows exactly what would be granted.
 */
@Component({
  selector: 'app-cli-auth',
  imports: [CopyClipboardComponent, NgTemplateOutlet, RouterLink],
  standalone: true,
  templateUrl: './cli-auth.component.html',
})
export class CliAuthComponent implements OnInit {
  mode: CliAuthMode = 'approve';
  scopes: AccessTokenScope[] = [];
  /** The signed in account the token will belong to. */
  username = '';
  readonly lifetimeHours = CLI_TOKEN_LIFETIME_HOURS;
  /** The checked address to post the token to; `null` is the paste flow. */
  redirectUri: string | null = null;
  /** Echoed back unchanged; only held in this page, never stored. */
  private state: string | null = null;

  created: AccessTokenCreated | null = null;
  /** The browser's own policy refused to send the form to the address: the CLI has certainly not got the token. */
  deliveryBlocked = false;
  /** After delivery the secret stays hidden until the person asks for it (the CLI did not get it). */
  tokenShown = false;
  authorizing = false;
  authorizeFailed = false;
  /** What happened to the previous CLI token: `null` while unknown or not yet asked. */
  revokeResult: 'revoked' | 'failed' | null = null;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly accessTokensApi: AccessTokensApi,
    private readonly authService: AuthService,
    private readonly delivery: CliTokenDeliveryService,
  ) {}

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    const requested = parseRequestedScopes(params.get('scopes'));
    // A link that names no scope the page knows gets the three repository scopes.
    this.scopes = requested.length > 0 ? requested : [...DEFAULT_CLI_SCOPES];
    this.username = this.authService.username ?? '';

    const rawRedirect = params.get('redirect_uri');
    if (rawRedirect !== null) {
      this.redirectUri = parseLoopbackRedirectUri(rawRedirect);
      // Present but not a loopback address: refuse, whatever else the link says. No token, no request to it.
      this.mode = this.redirectUri === null ? 'refused' : 'approve';
      this.state = params.get('state');
    }
  }

  get scopeDetails(): SelectableScope[] {
    return SELECTABLE_SCOPES.filter((s) => this.scopes.includes(s.scope));
  }

  get canAuthorize(): boolean {
    return this.mode === 'approve' && !this.authorizing;
  }

  /** On a refused link: the person chose to get a token to copy by hand anyway. Same screen, no address to post to. */
  usePasteInstead(): void {
    this.redirectUri = null;
    this.state = null;
    this.mode = 'approve';
  }

  decline(): void {
    if (!this.authorizing) {
      this.mode = 'declined';
    }
  }

  /** Creates the token, delivers it (or shows it), then (only then) revokes the previous CLI token. */
  authorize(): void {
    if (!this.canAuthorize) {
      return;
    }
    this.authorizing = true;
    this.authorizeFailed = false;
    this.accessTokensApi.createCliToken({ scopes: this.scopes as unknown as Set<AccessTokenScope> }).subscribe({
      next: (token) => {
        this.created = token;
        this.authorizing = false;
        if (this.redirectUri === null) {
          this.mode = 'pasted';
          this.tokenShown = true;
          // Nothing was sent anywhere that could be refused: the secret is on screen now.
          this.revokePrevious(token.id);
          return;
        }
        this.mode = 'delivered';
        // The previous token is only revoked once the browser has sent the new one on.
        void this.resend().then((sent) => {
          if (sent) {
            this.revokePrevious(token.id);
          }
        });
      },
      error: () => {
        // Nothing exists and nothing was sent: the person can try again or decline.
        this.authorizing = false;
        this.authorizeFailed = true;
      },
    });
  }

  /** Posts the token to the CLI again (a second tab). `false` when the browser refused to send it. */
  async resend(): Promise<boolean> {
    if (!this.created || this.redirectUri === null) {
      return false;
    }
    const sent = await this.delivery.postToken(this.redirectUri, this.created.token, this.state);
    this.deliveryBlocked = !sent;
    if (!sent) {
      // The browser refused to send it, so the CLI certainly has nothing: the person needs the secret now.
      this.tokenShown = true;
    }
    return sent;
  }

  showToken(): void {
    this.tokenShown = true;
  }

  private revokePrevious(tokenId: string): void {
    this.accessTokensApi.revokePreviousCliTokens(tokenId).subscribe({
      next: () => {
        this.revokeResult = 'revoked';
      },
      error: () => {
        this.revokeResult = 'failed';
      },
    });
  }
}
