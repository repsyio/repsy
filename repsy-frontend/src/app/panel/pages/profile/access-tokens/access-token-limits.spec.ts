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

import { countLive, DEFAULT_CLI_SCOPES, isExpired, MAX_LIVE_ACCESS_TOKENS } from './access-token-limits';

describe('access token limits', () => {
  const now = Date.parse('2026-06-01T00:00:00Z');

  it('treats a token as expired from its expiration instant on', () => {
    expect(isExpired({ expirationDate: '2026-06-01T00:00:00Z' }, now)).toBeTrue();
    expect(isExpired({ expirationDate: '2026-06-01T00:00:01Z' }, now)).toBeFalse();
  });

  it('counts only the tokens that have not expired', () => {
    const tokens = [
      { expirationDate: '2026-05-31T00:00:00Z' },
      { expirationDate: '2026-06-02T00:00:00Z' },
      { expirationDate: '2027-06-02T00:00:00Z' },
    ];

    expect(countLive(tokens, now)).toBe(2);
  });

  it('keeps the limit and the /cli/auth default scopes', () => {
    expect(MAX_LIVE_ACCESS_TOKENS).toBe(50);
    expect(DEFAULT_CLI_SCOPES).toEqual(['repo:read', 'repo:write', 'repo:manage']);
  });
});
