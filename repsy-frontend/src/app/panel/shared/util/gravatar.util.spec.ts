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

import { gravatarUrl } from './gravatar.util';

// The example address from the Gravatar documentation, and its MD5 hash.
const EMAIL = 'MyEmailAddress@example.com';
const EMAIL_HASH = '0bc83cb571cd1c50ba6f3e8a78ef1346';

describe('gravatarUrl', () => {
  it('hashes the email into the avatar URL', () => {
    expect(gravatarUrl('myemailaddress@example.com')).toBe(
      `https://www.gravatar.com/avatar/${EMAIL_HASH}?s=110&d=identicon`,
    );
  });

  it('trims and lower-cases the email before hashing, as Gravatar requires', () => {
    expect(gravatarUrl(`  ${EMAIL}  `)).toBe(gravatarUrl('myemailaddress@example.com'));
  });

  it('uses the requested size', () => {
    expect(gravatarUrl(EMAIL, 48)).toBe(`https://www.gravatar.com/avatar/${EMAIL_HASH}?s=48&d=identicon`);
  });

  it('falls back to the default identicon when there is no email', () => {
    const fallback = 'https://www.gravatar.com/avatar/?s=110&d=identicon';

    expect(gravatarUrl(null)).toBe(fallback);
    expect(gravatarUrl(undefined)).toBe(fallback);
    expect(gravatarUrl('')).toBe(fallback);
    expect(gravatarUrl(null, 32)).toBe('https://www.gravatar.com/avatar/?s=32&d=identicon');
  });
});
