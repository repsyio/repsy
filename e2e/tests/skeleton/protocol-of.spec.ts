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

/**
 * RPS-2245: `protocolOf` maps every panel repo type to a package protocol the seeders know. The Go
 * type is `GOLANG` on the panel API while the harness protocol is `go`, so the lower-cased type is
 * not enough there.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { PACKAGE_PROTOCOLS, protocolOf } from '../../src/seed/packages.js';
import { expect, test } from '@playwright/test';

test('every repo type maps to a package protocol', () => {
  for (const type of Object.values(RepoType)) {
    expect(PACKAGE_PROTOCOLS, type).toContain(protocolOf(type));
  }
});

test('the GOLANG repo type is the go protocol, in any case', () => {
  expect(protocolOf(RepoType.GOLANG)).toBe('go');
  expect(protocolOf('golang')).toBe('go');
  expect(protocolOf('go')).toBe('go');
});

test('a type that is no package protocol is refused', () => {
  expect(() => protocolOf('banana')).toThrow(/not a package protocol/);
});
