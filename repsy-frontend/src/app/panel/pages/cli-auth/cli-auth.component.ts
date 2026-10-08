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

import { AccessTokenCreated, AccessTokenScope } from '../../../../generated/api';
import { AccessTokenFormComponent } from '../../shared/components/access-token-form/access-token-form.component';
import { CopyClipboardComponent } from '../../shared/components/copy-clipboard/copy-clipboard.component';
import { parseRequestedScopes } from '../profile/access-tokens/access-token-scopes';

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
  public name = '';
  public scopes: AccessTokenScope[] = [];
  public created: AccessTokenCreated | null = null;

  constructor(private readonly route: ActivatedRoute) {}

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    this.name = params.get('name') ?? 'Repsy CLI';
    this.scopes = parseRequestedScopes(params.get('scopes'));
  }

  public scopesText(): string {
    return Array.from(this.created?.scopes ?? []).join(', ');
  }

  public onCreated(token: AccessTokenCreated): void {
    this.created = token;
  }
}
