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

import { loginUrlReturningTo, safeReturnUrl } from './return-url';

describe('safeReturnUrl', () => {
  ['/', '/repositories', '/my-repo/packages?tab=1#top', '/a%2Fb', '/users/ünï'].forEach((url) => {
    it(`accepts the in-app path ${url}`, () => {
      expect(safeReturnUrl(url)).toBe(url);
    });
  });

  [
    'https://evil.example/',
    'http://evil.example',
    '//evil.example',
    '///evil.example',
    '/\\evil.example',
    '\\\\evil.example',
    '/\t/evil.example',
    '/\n/evil.example',
    'javascript:alert(1)',
    '/javascript:alert(1)\u0000',
    'repositories',
    '',
  ].forEach((url) => {
    it(`refuses ${JSON.stringify(url)}`, () => {
      expect(safeReturnUrl(url)).toBeNull();
    });
  });

  [null, undefined, 42, ['/repositories'], {}].forEach((value) => {
    it(`refuses the non-string ${JSON.stringify(value)}`, () => {
      expect(safeReturnUrl(value)).toBeNull();
    });
  });
});

describe('loginUrlReturningTo', () => {
  it('remembers the page the user was on', () => {
    expect(loginUrlReturningTo('/repositories')).toBe('/login?returnUrl=%2Frepositories');
    expect(loginUrlReturningTo('/my-repo/packages?tab=1#top')).toBe(
      '/login?returnUrl=%2Fmy-repo%2Fpackages%3Ftab%3D1%23top',
    );
  });

  ['/', '/?returnUrl=%2Fx', '/#top', '/login', '/login?returnUrl=%2Fx', '/login/x'].forEach((url) => {
    it(`has nothing to remember on ${url}`, () => {
      expect(loginUrlReturningTo(url)).toBe('/login');
    });
  });

  it('does not treat a repository called login-x as the login page', () => {
    expect(loginUrlReturningTo('/login-x')).toBe('/login?returnUrl=%2Flogin-x');
  });

  [undefined, null, '', 'https://evil.example/', '//evil.example'].forEach((url) => {
    it(`gives the bare login for the unsafe ${JSON.stringify(url)}`, () => {
      expect(loginUrlReturningTo(url)).toBe('/login');
    });
  });
});
