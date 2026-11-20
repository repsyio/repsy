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
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { AccessTokensApi, AccessTokenScope } from '../../../../generated/api';
import { AuthService } from '../../../auth/pages/services/auth.service';
import {
  parseRequestedScopes,
  SELECTABLE_SCOPES,
  SelectableScope,
} from '../settings/access-tokens/access-token-scopes';
import { ConnectorRedirectService } from './connector-redirect.service';
import { connectorCallbackUrl, parseConnectorRedirectUri } from './connector-redirect-uri';

/**
 * What the page is doing:
 *
 * - `approve`: the consent screen. Nothing is created until "Authorize".
 * - `redirecting`: approved; the browser is on its way back to the application with a one-time code.
 * - `declined`: the person said no. Nothing was created and the browser stays here.
 * - `invalid`: the link lacks what a consent needs (a usable `redirect_uri`, a PKCE S256 challenge, a scope).
 * - `unavailable`: this server does not offer connected applications (the server answered 404).
 * - `unregistered`: the server does not know the address the link names.
 */
export type ConnectorAuthMode = 'approve' | 'redirecting' | 'declined' | 'invalid' | 'unavailable' | 'unregistered';

/** How long a connector token lives after its last use, and at most, fixed by the server. */
export const CONNECTOR_IDLE_DAYS = 90;
export const CONNECTOR_MAX_DAYS = 365;

/** The error code the server answers for a `redirect_uri` the operator did not register. */
const REDIRECT_NOT_REGISTERED = 'connectorRedirectUriNotRegistered';

/**
 * `/connector/auth?redirect_uri=&state=&scopes=&client_name=&code_challenge=&code_challenge_method=S256` (RPS-2660):
 * the consent page of a connected application, such as the remote MCP gateway of Repsy Cloud.
 *
 * It shows the account, the name the application gave itself, the scopes in plain words and the validity, and only on
 * "Authorize" asks the server for a one-time code, which the browser takes back to the application's registered
 * redirect together with its `state`. The application exchanges the code, server to server and with its PKCE
 * verifier, for its token: the token itself never reaches this page. "Decline" creates nothing and does not follow the
 * link's redirect, which nobody has checked at that point.
 */
@Component({
  selector: 'app-connector-auth',
  standalone: true,
  templateUrl: './connector-auth.component.html',
})
export class ConnectorAuthComponent implements OnInit {
  mode: ConnectorAuthMode = 'approve';
  scopes: AccessTokenScope[] = [];
  username = '';
  clientName = '';
  redirectUri: string | null = null;
  readonly idleDays = CONNECTOR_IDLE_DAYS;
  readonly maxDays = CONNECTOR_MAX_DAYS;
  authorizing = false;
  authorizeFailed = false;

  private state: string | null = null;
  private codeChallenge = '';

  constructor(
    private readonly route: ActivatedRoute,
    private readonly accessTokensApi: AccessTokensApi,
    private readonly authService: AuthService,
    private readonly redirectService: ConnectorRedirectService,
  ) {}

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    this.username = this.authService.username ?? '';
    this.scopes = parseRequestedScopes(params.get('scopes'));
    this.redirectUri = parseConnectorRedirectUri(params.get('redirect_uri'));
    this.state = params.get('state');
    this.codeChallenge = params.get('code_challenge') ?? '';
    const named = (params.get('client_name') ?? '').trim();
    this.clientName = named !== '' ? named : this.redirectHost();

    const valid =
      this.redirectUri !== null &&
      this.scopes.length > 0 &&
      this.codeChallenge !== '' &&
      params.get('code_challenge_method') === 'S256';
    this.mode = valid ? 'approve' : 'invalid';
  }

  get scopeDetails(): SelectableScope[] {
    return SELECTABLE_SCOPES.filter((s) => this.scopes.includes(s.scope));
  }

  get canAuthorize(): boolean {
    return this.mode === 'approve' && !this.authorizing;
  }

  decline(): void {
    if (!this.authorizing) {
      this.mode = 'declined';
    }
  }

  /** Asks for the one-time code, then sends the browser back to the application with it. */
  authorize(): void {
    if (!this.canAuthorize || this.redirectUri === null) {
      return;
    }
    const redirectUri = this.redirectUri;
    this.authorizing = true;
    this.authorizeFailed = false;
    this.accessTokensApi
      .createConnectorAuthorization({
        redirectUri,
        clientName: this.clientName,
        scopes: this.scopes as unknown as Set<AccessTokenScope>,
        codeChallenge: this.codeChallenge,
        codeChallengeMethod: 'S256',
      })
      .subscribe({
        next: (authorization) => {
          this.mode = 'redirecting';
          this.redirectService.redirect(connectorCallbackUrl(redirectUri, authorization.code, this.state));
        },
        error: (error: unknown) => {
          this.authorizing = false;
          this.onAuthorizeError(error);
        },
      });
  }

  private onAuthorizeError(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 404) {
      this.mode = 'unavailable';
    } else if (error instanceof HttpErrorResponse && error.error?.code === REDIRECT_NOT_REGISTERED) {
      this.mode = 'unregistered';
    } else {
      this.authorizeFailed = true;
    }
  }

  private redirectHost(): string {
    return this.redirectUri === null ? 'An application' : new URL(this.redirectUri).host;
  }
}
