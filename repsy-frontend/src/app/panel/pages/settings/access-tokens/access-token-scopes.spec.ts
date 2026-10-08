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
import { parseRequestedScopes, SELECTABLE_SCOPES } from './access-token-scopes';

describe('access token scopes', () => {
  it('never offers profile:read, which every token has', () => {
    expect(SELECTABLE_SCOPES.map((s) => s.scope)).not.toContain('profile:read');
  });

  it('keeps the known scopes of a list, in the order they are offered', () => {
    expect(parseRequestedScopes(' repo:manage, repo:write,repo:read')).toEqual([
      'repo:read',
      'repo:write',
      'repo:manage',
    ]);
  });

  it('does not offer scan:read and drops it from a link', () => {
    expect(SELECTABLE_SCOPES.map((s) => s.scope)).not.toContain('scan:read');
    expect(parseRequestedScopes('scan:read,repo:read')).toEqual(['repo:read']);
  });

  it('drops unknown values, profile:read and duplicates without a word', () => {
    expect(parseRequestedScopes('repo:read,admin,profile:read,repo:read,*,')).toEqual(['repo:read']);
  });

  it('gives nothing for a missing or empty list', () => {
    expect(parseRequestedScopes(null)).toEqual([]);
    expect(parseRequestedScopes(undefined)).toEqual([]);
    expect(parseRequestedScopes('')).toEqual([]);
  });
});
