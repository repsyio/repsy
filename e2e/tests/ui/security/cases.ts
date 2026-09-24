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
 * The protocols the security specs run over: the four whose packages the harness can seed over raw
 * HTTP (RPS-1255). One table so a badge, a scan section and a `/security` row are each checked for
 * every one of them.
 */
import { expect, type Page } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import type { PackageProtocol, PackageRef } from '../../../src/seed/packages.js';
import type { ProtocolListPage, ProtocolPages } from '../../../src/ui/pages/protocol.js';

export interface SecurityCase {
  protocol: PackageProtocol;
  type: RepoType;
  /**
   * The list level that shows one row per package with a security badge: `list`, except maven, whose
   * first level groups artifacts and whose badges sit on the artifact list of a group (`sublist`).
   */
  packageLevel: 'list' | 'sublist';
}

export const SECURITY_CASES: readonly SecurityCase[] = [
  { protocol: 'maven', type: RepoType.MAVEN, packageLevel: 'sublist' },
  { protocol: 'npm', type: RepoType.NPM, packageLevel: 'list' },
  { protocol: 'pypi', type: RepoType.PYPI, packageLevel: 'list' },
  { protocol: 'docker', type: RepoType.DOCKER, packageLevel: 'list' },
];

/** A URL that ends in `path` and the `#security` fragment: where a scan row or badge modal lands. */
export function atSecuritySection(path: string): RegExp {
  return new RegExp(`${path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}#security$`);
}

/** The list level of `pkg` that carries its security badge (see `SecurityCase.packageLevel`). */
export function packageListOf(
  pages: ProtocolPages,
  pkg: PackageRef,
  level: SecurityCase['packageLevel'],
): ProtocolListPage {
  return level === 'sublist' ? pages.sublist(pkg) : pages.list();
}

/**
 * Asserts that whatever was just clicked did NOT navigate away from `path` (the pathname read BEFORE
 * the click: a wrong navigation may already have landed by the time the click returns). It waits,
 * bounded (1.5 s), for the URL's path to differ and fails when it does. A route change is
 * asynchronous (guards, resolvers), so an immediate `toHaveURL` on the old page can pass a moment
 * before a wrong navigation lands.
 */
export async function expectStaysOnPage(page: Page, path: string): Promise<void> {
  const left = await page
    .waitForURL((url) => url.pathname !== path, { timeout: 1_500 })
    .then(
      () => true,
      () => false,
    );
  expect(left, `the click navigated away from ${path} to ${page.url()}`).toBe(false);
}
