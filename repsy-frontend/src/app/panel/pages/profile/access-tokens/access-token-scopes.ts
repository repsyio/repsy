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
}

/**
 * The scopes a person can ask for. `profile:read` is not listed: every token has it, so it is never
 * asked for. `repo:manage` is never implied by another scope, so it is a choice of its own.
 */
export const SELECTABLE_SCOPES: readonly SelectableScope[] = [
  { scope: 'repo:read', label: 'Read repositories', description: 'Download packages and read metadata.' },
  { scope: 'repo:write', label: 'Publish packages', description: 'Publish and push packages (and read).' },
  {
    scope: 'repo:manage',
    label: 'Manage repositories',
    description: 'Delete packages and change repository settings (and write, read).',
  },
  { scope: 'scan:read', label: 'Read scan results', description: 'Read security scan results.' },
];

/**
 * The scopes of a comma separated list (the `scopes` query parameter of `/cli/auth`) that can be
 * asked for. A value that is not one of them, `profile:read` included, is dropped without a word:
 * the page shows what is actually selected, so a link can never grant more than it displays.
 */
export function parseRequestedScopes(csv: string | null | undefined): AccessTokenScope[] {
  const requested = new Set((csv ?? '').split(',').map((s) => s.trim()));
  return SELECTABLE_SCOPES.map((s) => s.scope).filter((scope) => requested.has(scope));
}
