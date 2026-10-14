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
import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import moment from 'moment';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

import {
  AccessTokenCreated,
  AccessTokenForm,
  AccessTokenListItem,
  AccessTokensApi,
  AccessTokenScope,
} from '../../../../generated/api';
import { AuthService } from '../../../auth/pages/services/auth.service';
import { AccessTokenFormComponent } from '../../shared/components/access-token-form/access-token-form.component';
import { CopyClipboardComponent } from '../../shared/components/copy-clipboard/copy-clipboard.component';
import {
  countLive,
  DEFAULT_CLI_SCOPES,
  isExpired,
  MAX_ACCESS_TOKEN_NAME_LENGTH,
  MAX_LIVE_ACCESS_TOKENS,
} from '../settings/access-tokens/access-token-limits';
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
 * - `paste`: the link has no `redirect_uri`. Today's flow: a form, then the secret on screen to copy by hand.
 * - `approve`: the link has a valid loopback `redirect_uri`. An approval screen; nothing is created until "Approve".
 * - `delivered`: approved; the token was created and posted to the CLI.
 * - `declined`: the person said no. Nothing was created.
 * - `refused`: the link has a `redirect_uri` that is not a loopback address. Nothing was created.
 */
export type CliAuthMode = 'paste' | 'approve' | 'delivered' | 'declined' | 'refused';

/** How long a token created here lives: as far ahead as the backend allows (the same default as the create form). */
const TOKEN_LIFETIME_DAYS = 365;

/**
 * `/cli/auth?state=&redirect_uri=&name=&scopes=` (RPS-1905, RPS-2039): the page the CLI opens in a browser.
 *
 * With a loopback `redirect_uri` it is an approval screen: it shows the account, the token name, the scopes in plain
 * words and the expiry, and only on "Approve" creates the token (the ordinary create call: the same name, expiry and
 * 50 token rules) and POSTs the secret in a form body to the CLI, with `state` unchanged. The server keeps no `state`.
 * Without a `redirect_uri` it is the paste flow, unchanged. A scope the page does not know is dropped, so the screen
 * always shows exactly what would be granted.
 */
@Component({
  selector: 'app-cli-auth',
  imports: [AccessTokenFormComponent, CopyClipboardComponent, DatePipe, NgTemplateOutlet, RouterLink],
  standalone: true,
  templateUrl: './cli-auth.component.html',
})
export class CliAuthComponent implements OnInit {
  mode: CliAuthMode = 'paste';
  name = '';
  scopes: AccessTokenScope[] = [];
  /** The signed in account the token will belong to. */
  username = '';
  expiresAt: Date = new Date();
  /** The checked address to post the token to (`approve` and `delivered` only). */
  redirectUri: string | null = null;
  /** Echoed back unchanged; only held in this page, never stored. */
  private state: string | null = null;

  created: AccessTokenCreated | null = null;
  /** The browser's own policy refused to send the form to the address: the CLI has certainly not got the token. */
  deliveryBlocked = false;
  /** After delivery the secret stays hidden until the person asks for it (the CLI did not get it). */
  tokenShown = false;
  /** On a refused link: the person chose to create a token by hand anyway. */
  pasteFallback = false;
  blockedReason: string | null = null;
  /** The check of how many live tokens exist is under way / could not be read. */
  checkingRoom = true;
  roomCheckFailed = false;

  /** Live tokens with the name this page would create. */
  sameNameTokens: AccessTokenListItem[] = [];
  /** "Revoke the older token with the same name": on by default, removed only after the new token exists. */
  revokeOld = true;
  approving = false;
  approveFailed = false;
  /** What happened to the older tokens after delivery: `null` while unknown or not asked. */
  revokeResult: 'revoked' | 'failed' | null = null;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly accessTokensApi: AccessTokensApi,
    private readonly authService: AuthService,
    private readonly delivery: CliTokenDeliveryService,
  ) {}

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    // Shown as text (Angular escapes it) and cut to what the backend accepts.
    this.name = (params.get('name') ?? 'Repsy CLI').trim().slice(0, MAX_ACCESS_TOKEN_NAME_LENGTH);
    const requested = parseRequestedScopes(params.get('scopes'));
    // A link that names no scope the page knows gets the three repository scopes, ticked.
    this.scopes = requested.length > 0 ? requested : [...DEFAULT_CLI_SCOPES];
    this.username = this.authService.username ?? '';
    this.expiresAt = moment.utc().add(TOKEN_LIFETIME_DAYS, 'days').milliseconds(0).toDate();

    const rawRedirect = params.get('redirect_uri');
    if (rawRedirect !== null) {
      this.redirectUri = parseLoopbackRedirectUri(rawRedirect);
      // Present but not a loopback address: refuse, whatever else the link says. No token, no request to it.
      this.mode = this.redirectUri === null ? 'refused' : 'approve';
      this.state = params.get('state');
    }
    if (this.mode !== 'refused') {
      this.checkRoom();
    }
  }

  get scopeDetails(): SelectableScope[] {
    return SELECTABLE_SCOPES.filter((s) => this.scopes.includes(s.scope));
  }

  /** Approve is possible when nothing blocks it. */
  get canApprove(): boolean {
    return this.mode === 'approve' && !this.approving && !this.checkingRoom && !this.blockedReason;
  }

  /** Blocks the create button when the user already holds the most tokens that have not expired. */
  private checkRoom(): void {
    this.accessTokensApi.listAccessTokens(0, 100, ['expirationDate,desc']).subscribe({
      next: (r) => {
        this.checkingRoom = false;
        const tokens = r.content ?? [];
        this.sameNameTokens = tokens.filter((t) => t.name === this.name && !isExpired(t));
        if (countLive(tokens) >= MAX_LIVE_ACCESS_TOKENS) {
          // Creating comes first and revoking after it, so a full list is not made room for here: the new token
          // must never be lost to a revoke that went through and a create that did not.
          this.blockedReason = `You already have ${MAX_LIVE_ACCESS_TOKENS} access tokens that have not expired, the most allowed. Revoke one under Settings first.`;
        }
      },
      error: () => {
        this.checkingRoom = false;
        this.roomCheckFailed = true;
      },
    });
  }

  scopesText(): string {
    return Array.from(this.created?.scopes ?? []).join(', ');
  }

  onCreated(token: AccessTokenCreated): void {
    this.created = token;
    this.tokenShown = true;
  }

  usePasteInstead(): void {
    this.pasteFallback = true;
    this.checkRoom();
  }

  decline(): void {
    if (!this.approving) {
      this.mode = 'declined';
    }
  }

  /** Creates the token, posts it to the CLI, then (only then) revokes the older token of the same name. */
  approve(): void {
    if (!this.canApprove || this.redirectUri === null) {
      return;
    }
    const payload: AccessTokenForm = {
      name: this.name,
      // The generated model types a unique list as a Set, but HttpClient serialises a Set as `{}`: send the array.
      scopes: this.scopes as unknown as Set<AccessTokenScope>,
      expirationDate: this.expiresAt.toISOString(),
    };
    this.approving = true;
    this.approveFailed = false;
    this.accessTokensApi.createAccessToken(payload).subscribe({
      next: (token) => {
        this.created = token;
        this.mode = 'delivered';
        this.approving = false;
        // The older token is only revoked once the browser has sent the new one on.
        void this.resend().then((sent) => {
          if (sent && this.revokeOld && this.sameNameTokens.length > 0) {
            this.revokePrevious();
          }
        });
      },
      error: () => {
        // Nothing exists and nothing was sent: the person can try again or decline.
        this.approving = false;
        this.approveFailed = true;
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

  private revokePrevious(): void {
    const revocations = this.sameNameTokens.map((t) =>
      this.accessTokensApi.revokeAccessToken(t.id).pipe(
        map(() => true),
        catchError(() => of(false)),
      ),
    );
    forkJoin(revocations).subscribe((results) => {
      this.revokeResult = results.every(Boolean) ? 'revoked' : 'failed';
    });
  }
}
