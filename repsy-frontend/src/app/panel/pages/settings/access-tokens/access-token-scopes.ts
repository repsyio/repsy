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

import { AccessTokenScope } from '../../../../../generated/api';

export interface SelectableScope {
  readonly scope: AccessTokenScope;
  readonly label: string;
  readonly description: string;
  /**
   * Only an administrator can use it: the server intersects a token's scopes with its owner's role on
   * every request, so for anyone else the scope would be accepted and then refused on every call.
   */
  readonly adminOnly?: boolean;
}

/**
 * The scopes a person can ask for. `profile:read` is not listed: every token has it, so it is never
 * asked for. `repo:manage` is never implied by another scope, so it is a choice of its own. `scan:read` reads
 * scan results only (RPS-2260); it needs no role, so everybody is offered it.
 */
export const SELECTABLE_SCOPES: readonly SelectableScope[] = [
  { scope: 'repo:read', label: 'Read repositories', description: 'Download packages and read metadata.' },
  { scope: 'repo:write', label: 'Publish packages', description: 'Publish and push packages (and read).' },
  {
    scope: 'repo:manage',
    label: 'Manage repositories',
    description: 'Delete packages and change repository settings (and write, read).',
    adminOnly: true,
  },
  {
    scope: 'scan:read',
    label: 'Read scan results',
    description: 'Reads scan results and their state; does not read the repository itself.',
  },
];

/**
 * The scopes a `/cli/auth` link can ask for. `scan:read` is offered in the form but not yet by a link: what the approval
 * screen of the CLI grants is decided there (RPS-2039), so a link that names it still gets it dropped.
 */
const LINKABLE_SCOPES: readonly AccessTokenScope[] = ['repo:read', 'repo:write', 'repo:manage'];

/**
 * The scopes {@link SELECTABLE_SCOPES} that a user with this role can use. Only the panel hides the
 * rest: the server does not refuse them when a token is created, because the role is read again on
 * every request, so a token made for a user who is promoted later starts to manage at once.
 */
export function selectableScopesFor(isAdmin: boolean): readonly SelectableScope[] {
  return SELECTABLE_SCOPES.filter((s) => isAdmin || !s.adminOnly);
}

/**
 * The scopes of a comma separated list (the `scopes` query parameter of `/cli/auth`) that can be
 * asked for. A value that is not one of them, `profile:read` and `scan:read` included, is dropped without a word:
 * the page shows what is actually selected, so a link can never grant more than it displays.
 */
export function parseRequestedScopes(csv: string | null | undefined): AccessTokenScope[] {
  const requested = new Set((csv ?? '').split(',').map((s) => s.trim()));
  return LINKABLE_SCOPES.filter((scope) => requested.has(scope));
}
