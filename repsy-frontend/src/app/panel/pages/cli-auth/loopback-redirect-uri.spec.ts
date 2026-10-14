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

import { parseLoopbackRedirectUri } from './loopback-redirect-uri';

describe('parseLoopbackRedirectUri', () => {
  const accepted = [
    'http://127.0.0.1:8123/callback',
    'http://127.0.0.1:1/',
    'http://127.0.0.1:65535/cb',
    'http://127.0.0.1:54321/callback?x=1',
    'http://127.0.0.1:54321/a/b/c',
    'http://[::1]:8123/callback',
    'http://[::1]:65535/',
  ];

  accepted.forEach((uri) => {
    it(`accepts ${uri}`, () => {
      expect(parseLoopbackRedirectUri(uri)).toBe(uri);
    });
  });

  const refused: [string, string][] = [
    ['a public host', 'http://example.com:8123/callback'],
    ['a public host with a loopback path', 'http://evil.com:8123/127.0.0.1'],
    ['a host that starts like loopback', 'http://127.0.0.1.evil.com:8123/callback'],
    ['loopback as user info', 'http://127.0.0.1@evil.com:8123/callback'],
    ['loopback user info with a port', 'http://127.0.0.1:80@evil.com:8123/callback'],
    ['an empty user info', 'http://@127.0.0.1:8123/callback'],
    ['user info on loopback', 'http://user:pass@127.0.0.1:8123/callback'],
    ['loopback in the fragment', 'http://evil.com:8123/#127.0.0.1'],
    ['a fragment', 'http://127.0.0.1:8123/callback#frag'],
    ['an empty fragment', 'http://127.0.0.1:8123/callback#'],
    ['loopback as a sub domain of a lookalike', 'http://evil.com#127.0.0.1:8123/callback'],
    ['the short IPv4 form', 'http://127.1:8123/callback'],
    ['the decimal IPv4 form', 'http://2130706433:8123/callback'],
    ['the hex IPv4 form', 'http://0x7f.0.0.1:8123/callback'],
    ['another 127 address', 'http://127.0.0.2:8123/callback'],
    ['localhost', 'http://localhost:8123/callback'],
    ['a long IPv6 loopback', 'http://[0:0:0:0:0:0:0:1]:8123/callback'],
    ['an IPv4 mapped IPv6 loopback', 'http://[::ffff:127.0.0.1]:8123/callback'],
    ['the unspecified address', 'http://0.0.0.0:8123/callback'],
    ['https', 'https://127.0.0.1:8123/callback'],
    ['another scheme', 'ftp://127.0.0.1:8123/callback'],
    ['javascript', 'javascript:alert(1)'],
    ['a data URL', 'data:text/html,127.0.0.1'],
    ['an upper case scheme', 'HTTP://127.0.0.1:8123/callback'],
    ['a missing port', 'http://127.0.0.1/callback'],
    ['a missing port with a colon', 'http://127.0.0.1:/callback'],
    ['port 0', 'http://127.0.0.1:0/callback'],
    ['a port above 65535', 'http://127.0.0.1:65536/callback'],
    ['a huge port', 'http://127.0.0.1:99999999/callback'],
    ['a negative port', 'http://127.0.0.1:-1/callback'],
    ['a port with a leading zero', 'http://127.0.0.1:08123/callback'],
    ['a non numeric port', 'http://127.0.0.1:abc/callback'],
    ['the default port', 'http://127.0.0.1:80/callback'],
    ['no path', 'http://127.0.0.1:8123'],
    ['a protocol relative path', 'http://127.0.0.1:8123//evil.com/x'],
    ['a backslash', 'http://127.0.0.1:8123\\@evil.com/'],
    ['a backslash in the path', 'http://127.0.0.1:8123/a\\b'],
    ['an encoded slash', 'http://127.0.0.1:8123/a%2Fb'],
    ['an encoded backslash', 'http://127.0.0.1:8123/a%5cb'],
    ['an encoded NUL', 'http://127.0.0.1:8123/a%00b'],
    ['a dot segment', 'http://127.0.0.1:8123/a/../b'],
    ['a space', 'http://127.0.0.1:8123/call back'],
    ['a leading space', ' http://127.0.0.1:8123/callback'],
    ['a trailing space', 'http://127.0.0.1:8123/callback '],
    ['a tab inside the host', 'http://127.0.0.\t1:8123/callback'],
    ['a newline', 'http://127.0.0.1:8123/call\nback'],
    ['two URLs', 'http://127.0.0.1:8123/callback http://evil.com/'],
    ['an empty string', ''],
    ['only spaces', '   '],
  ];

  refused.forEach(([label, uri]) => {
    it(`refuses ${label}`, () => {
      expect(parseLoopbackRedirectUri(uri)).toBeNull();
    });
  });

  it('refuses a missing value', () => {
    expect(parseLoopbackRedirectUri(null)).toBeNull();
    expect(parseLoopbackRedirectUri(undefined)).toBeNull();
    expect(parseLoopbackRedirectUri(42 as unknown as string)).toBeNull();
  });
});
