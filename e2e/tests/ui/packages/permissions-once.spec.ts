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

/**
 * PKG-perm-01 (RPS-1305): every protocol's page requests the repository permissions exactly once on a
 * cold load. The shell component of a protocol used to subscribe to the replaying `currentRepo$` AND
 * load the permissions by hand, so the request went out twice and the second answer made the pages
 * below it re-emit (the Maven browser lost its directory, RPS-1297). One test per protocol, over the
 * same descriptors as the package scenarios.
 */
import type { Request } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

test.describe('Repository permissions', { tag: '@packages' }, () => {
  for (const descriptor of Object.values(DESCRIPTORS)) {
    const { protocol } = descriptor;

    test(`PKG-perm-01 ${protocol}: a cold load of the list page requests the permissions once`, async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      const repo = await seeder.createRepo(
        RepoType[protocol.toUpperCase() as keyof typeof RepoType],
      );
      const pkg = await seedPackage(repo);
      const permissions = `/api/repos/${repo.name}/permissions`;
      const requests: string[] = [];
      adminPage.on('request', (request: Request) => {
        if (new URL(request.url()).pathname === permissions) {
          requests.push(request.url());
        }
      });

      const list = protocolPages(adminPage, descriptor, repo.name).list();
      await list.goto();
      await list.expectRow(pkg);
      // Both requests of the old double load are sent while the shell initialises, before the list is on screen.

      expect(requests, `requests to ${permissions}`).toHaveLength(1);
    });
  }
});
