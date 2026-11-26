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
import { Component, EventEmitter, Input, Output } from '@angular/core';

import { AccessTokenScope } from '../../../../../generated/api';
import { SelectableScope } from '../../../pages/settings/access-tokens/access-token-scopes';

/** One permission's card was ticked or unticked. */
export interface PermissionToggle {
  readonly scope: AccessTokenScope;
  readonly checked: boolean;
}

/**
 * The permissions of an authorize screen as selectable cards (RPS-2755): a labelled checkbox, the permission's name and
 * scope, and one sentence on what it opens. Shared by the MCP consent page and the CLI sign-in page, so the two look
 * alike. A fixed permission is always granted and cannot be unticked.
 */
@Component({
  selector: 'app-permission-cards',
  standalone: true,
  templateUrl: './permission-cards.component.html',
})
export class PermissionCardsComponent {
  @Input({ required: true }) options: readonly SelectableScope[] = [];
  @Input() granted: ReadonlySet<AccessTokenScope> = new Set();
  @Input() fixed: AccessTokenScope | null = null;
  @Input() disabled = false;
  /** The prefix of the cards' `data-testid`s, so each page keeps its own. */
  @Input() testIdPrefix = 'permission';
  @Output() toggled = new EventEmitter<PermissionToggle>();

  isFixed(scope: AccessTokenScope): boolean {
    return scope === this.fixed;
  }

  isGranted(scope: AccessTokenScope): boolean {
    return this.isFixed(scope) || this.granted.has(scope);
  }

  onChange(scope: AccessTokenScope, checked: boolean): void {
    if (!this.isFixed(scope)) {
      this.toggled.emit({ scope, checked });
    }
  }
}
