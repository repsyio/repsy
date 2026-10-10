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
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { AccessTokenCreated, AccessTokensApi, AccessTokenScope } from '../../../../generated/api';
import { AccessTokenFormComponent } from '../../shared/components/access-token-form/access-token-form.component';
import { CopyClipboardComponent } from '../../shared/components/copy-clipboard/copy-clipboard.component';
import {
  countLive,
  DEFAULT_CLI_SCOPES,
  MAX_ACCESS_TOKEN_NAME_LENGTH,
  MAX_LIVE_ACCESS_TOKENS,
} from '../settings/access-tokens/access-token-limits';
import { parseRequestedScopes } from '../settings/access-tokens/access-token-scopes';

/**
 * `/cli/auth?name=&scopes=&state=`: the page the CLI opens in a browser so a person can create an
 * access token for it and paste it back. The name and the scopes come pre-filled but are only
 * suggestions: the person sees them, can change them, and creates the token with one click. A
 * scope the page does not know is dropped. `state` is recognised and deliberately ignored: nothing
 * is sent back to the CLI from here (the localhost callback is a separate change).
 */
@Component({
  selector: 'app-cli-auth',
  imports: [AccessTokenFormComponent, CopyClipboardComponent, RouterLink],
  standalone: true,
  templateUrl: './cli-auth.component.html',
})
export class CliAuthComponent implements OnInit {
  name = '';
  scopes: AccessTokenScope[] = [];
  created: AccessTokenCreated | null = null;
  blockedReason: string | null = null;
  /** The check of how many live tokens exist is under way / could not be read. */
  checkingRoom = true;
  roomCheckFailed = false;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly accessTokensApi: AccessTokensApi,
  ) {}

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    // Shown as text (Angular escapes it) and cut to what the backend accepts.
    this.name = (params.get('name') ?? 'Repsy CLI').trim().slice(0, MAX_ACCESS_TOKEN_NAME_LENGTH);
    const requested = parseRequestedScopes(params.get('scopes'));
    // A link that names no scope the page knows gets the three repository scopes, ticked.
    this.scopes = requested.length > 0 ? requested : [...DEFAULT_CLI_SCOPES];
    this.checkRoom();
  }

  /** Blocks the create button when the user already holds the most tokens that have not expired. */
  private checkRoom(): void {
    this.accessTokensApi.listAccessTokens(0, 100, ['expirationDate,desc']).subscribe({
      next: (r) => {
        this.checkingRoom = false;
        if (countLive(r.content ?? []) >= MAX_LIVE_ACCESS_TOKENS) {
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
  }
}
