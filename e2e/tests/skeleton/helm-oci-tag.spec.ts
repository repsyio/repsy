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

/**
 * RPS-2252: a chart version with build metadata is pushed under an OCI tag without the `+`, which
 * the distribution grammar does not allow.
 */
import { ociTagOf } from '../../src/clients/helm-raw.js';
import { expect, test } from '@playwright/test';

test('the + of build metadata becomes _ in the OCI tag', () => {
  expect(ociTagOf('1.2.3+build.5')).toBe('1.2.3_build.5');
  expect(ociTagOf('1.0.0-rc.1+a+b')).toBe('1.0.0-rc.1_a_b');
});

test('a version without build metadata is its own tag', () => {
  expect(ociTagOf('1.0.0-rc.1')).toBe('1.0.0-rc.1');
});
