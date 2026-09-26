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
 * RPS-1604: a panel access token is bound to the user it was issued to, on EVERY route that takes it.
 * The routes under `/api/repos/**` and `/api/<format>/**` (`@RepoOperation`) and the Docker panel routes
 * resolve the caller through the protocol auth service and used to read the username claim alone, so an
 * access token kept working there for up to 30 minutes after a password change (the profile routes
 * refused it at once), and the name a user had freed by a rename handed the old token to the next user
 * of that name. Now the token's `token_version` and its subject (the user id) have to match the user
 * row everywhere, and a mismatch answers `sessionExpired`.
 *
 * The probes are raw requests with the access token the login handed out, kept across the event.
 */
import { createPanelBackend } from '../../src/api/backend-registry.js';
import { RepoType, UserRole } from '../../src/api/panel-api.js';
import { apiUrl, edgeRequest } from '../../src/clients/edge-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const NEW_PASSWORD_SUFFIX = 'Changed1!';

/** One `@RepoOperation` route per way the caller is resolved, and one that is not a repo route. */
const ROUTES: ReadonlyArray<{
  name: string;
  repoType: RepoType | undefined;
  path: (repoName: string) => string;
}> = [
  {
    name: 'a generic @RepoOperation route',
    repoType: RepoType.MAVEN,
    path: (repoName) => `/api/repos/${repoName}/settings`,
  },
  {
    name: 'a Docker panel route',
    repoType: RepoType.DOCKER,
    path: (repoName) => `/api/docker/images/${repoName}`,
  },
  { name: 'the profile', repoType: undefined, path: () => '/api/profile' },
];

/** The route's URL path, on a private repo of its type where it needs one. */
async function pathOf(seeder: Seeder, route: (typeof ROUTES)[number]): Promise<string> {
  const repo = route.repoType
    ? await seeder.createRepo(route.repoType, { privateRepo: true })
    : undefined;
  return route.path(repo?.name ?? '');
}

async function call(path: string, token: string): Promise<{ status: number; text: string }> {
  const res = await edgeRequest(apiUrl(path), { headers: { Authorization: `Bearer ${token}` } });
  return { status: res.status, text: res.text };
}

test.describe('panel access token binding (RPS-1604)', { tag: ['@smoke'] }, () => {
  for (const route of ROUTES) {
    test(`a password change ends the panel token on ${route.name}`, async ({ seeder }) => {
      const admin = await seeder.createUser({ role: UserRole.ADMIN });
      const path = await pathOf(seeder, route);
      const adminApi = await createPanelBackend();
      const login = await adminApi.login(admin.username, admin.password);
      const token = login.token as string;

      const before = await call(path, token);
      expect(before.status, `before the change: ${before.text}`).toBe(200);

      await adminApi.changeOwnPassword(`${admin.password}${NEW_PASSWORD_SUFFIX}`);

      const after = await call(path, token);
      expect(after.status, `after the change: ${after.text}`).toBe(401);
      expect(after.text).toContain('sessionExpired');
    });

    test(`a reused username does not inherit the panel token on ${route.name}`, async ({
      seeder,
    }) => {
      const former = await seeder.createUser({ role: UserRole.ADMIN });
      const path = await pathOf(seeder, route);
      const formerApi = await createPanelBackend();
      const token = (await formerApi.login(former.username, former.password)).token as string;

      expect((await call(path, token)).status).toBe(200);

      const renamed = await formerApi.rawRequest('PUT', '/api/profile/username', {
        username: seeder.reserveUsername(),
      });
      expect(renamed.status, `rename: ${JSON.stringify(renamed.body)}`).toBe(200);
      // A fresh user starts at the token_version the old token still carries.
      const successor = await seeder.createUser({
        username: former.username,
        role: UserRole.ADMIN,
      });

      const stale = await call(path, token);
      expect(stale.status, stale.text).toBe(401);
      expect(stale.text).toContain('sessionExpired');

      const successorToken = (
        await (await createPanelBackend()).login(successor.username, successor.password)
      ).token as string;
      expect((await call(path, successorToken)).status).toBe(200);
    });
  }
});
