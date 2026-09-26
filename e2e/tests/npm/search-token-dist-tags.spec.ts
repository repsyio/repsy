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
 * The npm wire routes RPS-1343, RPS-1344, RPS-1361 and RPS-1362 changed, pinned with raw HTTP (no
 * `npm` client; the clients' own commands are in `tests/npm-clients`):
 *
 *  - `GET /-/v1/search` (RPS-1343): the `author:`, `maintainer:`, `is:`/`not:` and `boost-exact:`
 *    qualifiers filter, and a text of qualifiers Repsy cannot filter on matches NO package (it used
 *    to match every one).
 *  - `GET /-/v1/search` (RPS-1344): `size=0` answers no objects and the total, a `from` beyond the
 *    last result (or beyond what an int holds) answers an empty page, and a `size`/`from` that is no
 *    whole number of 0 or more is a 400 (`invalidSearchParameter`), not the first page.
 *  - `DELETE /-/user/token/<token>` (RPS-1361): `npm logout` and `pnpm logout` call it. It revokes the
 *    token of a login (`200 {"ok": true}`, and the token is refused from then on), refuses the secret
 *    of a deploy token (`403`, it is managed in the panel; the body explains that in `error`, RPS-1391) and needs credentials (`401`).
 *  - `PUT`/`DELETE /-/package/<pkg>/dist-tags/<tag>` (RPS-1362): answer `{"ok": true, "id": ...,
 *    "dist-tags": {...}}`, because yarn classic takes an answer without `ok` for a failure.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { env } from '../../src/env.js';
import {
  adminCredential,
  buildPublishDocument,
  buildTarball,
  npmAuthHeader,
  rawGetPath,
  rawLogin,
  rawPublish,
  rawRequestPath,
} from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface SearchResult {
  total: number;
  objects: Array<{ package: { name: string; scope: string }; searchScore: number }>;
}

/** A repo (public by default: the search and the token routes need credentials only when asked). */
async function newRepo(seeder: Seeder, privateRepo = false): Promise<string> {
  return (await seeder.createRepo(RepoType.NPM, { privateRepo })).name;
}

async function publish(
  repoName: string,
  packageName: string,
  version: string,
  extra: Record<string, unknown> = {},
): Promise<void> {
  const document = buildPublishDocument({
    repoName,
    packageName,
    version,
    tarballBytes: buildTarball({ packageName, version }),
    extra,
  });
  const res = await rawPublish(repoName, adminCredential(), packageName, document);
  expect(res.status, `publish ${packageName}@${version}: ${res.msgId ?? ''}`).toBe(200);
}

async function search(repoName: string, query: string) {
  const res = await rawGetPath(repoName, adminCredential(), `-/v1/search?${query}`);
  return { status: res.status, text: res.body.toString('utf8') };
}

async function names(repoName: string, text: string): Promise<string[]> {
  const res = await search(repoName, `text=${encodeURIComponent(text)}`);
  expect(res.status, `search "${text}"`).toBe(200);
  return (JSON.parse(res.text) as SearchResult).objects.map((entry) => entry.package.name).sort();
}

test.describe('npm search qualifiers and paging (raw HTTP)', () => {
  test(
    'author:, maintainer:, is:unstable and a qualifier-only text filter (RPS-1343)',
    { tag: ['@search'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const run = seeder.runId;
      const left = `e2e-${run}-left`;
      const right = `e2e-${run}-right`;
      await publish(repo, left, '1.0.0', {
        author: { name: 'Ann', email: 'ann@example.com' },
        maintainers: [{ name: 'ann', email: 'ann@example.com' }],
      });
      await publish(repo, right, '0.3.0', {
        author: { name: 'Bob' },
        maintainers: [{ name: 'bob' }],
      });

      expect(await names(repo, 'author:ann')).toEqual([left]);
      expect(await names(repo, 'author:ANN@example.com')).toEqual([left]);
      expect(await names(repo, 'maintainer:bob')).toEqual([right]);
      expect(await names(repo, 'maintainer:nobody')).toEqual([]);
      expect(await names(repo, 'is:unstable')).toEqual([right]);
      expect(await names(repo, 'not:unstable')).toEqual([left]);
      expect(await names(repo, 'is:deprecated')).toEqual([]);
      expect(await names(repo, 'not:deprecated')).toEqual([left, right].sort());
      expect(await names(repo, `is:unstable ${run}`)).toEqual([right]);

      // Qualifiers Repsy cannot filter on used to match every package.
      for (const text of ['is:shiny', 'not:shiny', 'author:', 'maintainer:', 'boost-exact:maybe']) {
        const res = await search(repo, `text=${encodeURIComponent(text)}`);
        expect(res.status, text).toBe(200);
        const body = JSON.parse(res.text) as SearchResult;
        expect(body.objects, `"${text}" matches nothing`).toEqual([]);
        expect(body.total, text).toBe(0);
      }
      expect(await names(repo, 'is:shiny left')).toEqual([left]);
      expect(await names(repo, '')).toEqual([left, right].sort());
    },
  );

  test(
    'boost-exact:false takes the whole-name bonus off the score (RPS-1343)',
    { tag: ['@search'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-exact`;
      await publish(repo, name, '1.0.0');

      const boosted = JSON.parse((await search(repo, `text=${name}`)).text) as SearchResult;
      const plain = JSON.parse(
        (await search(repo, `text=${encodeURIComponent(`${name} boost-exact:false`)}`)).text,
      ) as SearchResult;

      expect(boosted.objects[0]?.searchScore).toBeGreaterThan(100_000);
      expect(plain.objects[0]?.searchScore).toBeLessThan(100_000);
      expect(plain.objects.map((entry) => entry.package.name)).toEqual([name]);
    },
  );

  test(
    'size=0, a from beyond the end and a size or from that is no number (RPS-1344)',
    { tag: ['@search', '@negative'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const run = seeder.runId;
      for (const label of ['a', 'b', 'c']) {
        await publish(repo, `e2e-${run}-${label}`, '1.0.0');
      }

      const none = JSON.parse((await search(repo, 'size=0')).text) as SearchResult;
      expect(none.objects, 'size=0 asks for no objects').toEqual([]);
      expect(none.total).toBe(3);

      for (const from of ['3', '10', '2147483647', '99999999999', '99999999999999999999']) {
        const res = await search(repo, `from=${from}`);
        expect(res.status, `from=${from}`).toBe(200);
        const body = JSON.parse(res.text) as SearchResult;
        expect(body.objects, `from=${from} is beyond the last result`).toEqual([]);
        expect(body.total, `from=${from}`).toBe(3);
      }
      expect(
        (JSON.parse((await search(repo, 'from=2')).text) as SearchResult).objects,
      ).toHaveLength(1);
      expect(
        (JSON.parse((await search(repo, 'size=251')).text) as SearchResult).objects,
      ).toHaveLength(3);

      for (const query of ['size=many', 'size=-1', 'size=1.5', 'from=x', 'from=-3', 'from=1e3']) {
        const res = await search(repo, query);
        expect(res.status, query).toBe(400);
        expect(res.text, query).toContain('invalidSearchParameter');
      }
    },
  );
});

test.describe('npm token revocation (raw HTTP)', () => {
  test(
    'DELETE /-/user/token/<token> revokes the token of a login (RPS-1361)',
    { tag: ['@token'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder, true);
      const { status, token } = await rawLogin(repo, env.adminUsername, env.adminPassword);
      expect(status).toBe(201);
      expect(token).toBeTruthy();
      const bearer = { Authorization: `Bearer ${token}` };

      const before = await rawRequestPath(repo, 'GET', '-/whoami', bearer);
      expect(before.status).toBe(200);
      expect(JSON.parse(before.body.toString('utf8'))).toEqual({ username: env.adminUsername });

      const revoked = await rawRequestPath(repo, 'DELETE', `-/user/token/${token}`, bearer);
      expect(revoked.status).toBe(200);
      expect(JSON.parse(revoked.body.toString('utf8'))).toEqual({ ok: true });

      expect((await rawRequestPath(repo, 'GET', '-/whoami', bearer)).status).toBe(401);
      expect((await rawRequestPath(repo, 'GET', 'nothing-here', bearer)).status).toBe(401);
      expect(
        (await rawRequestPath(repo, 'DELETE', `-/user/token/${token}`, bearer)).status,
        'a revoked token cannot revoke itself again',
      ).toBe(401);

      // The password still logs in: only that token was revoked.
      expect((await rawLogin(repo, env.adminUsername, env.adminPassword)).status).toBe(201);
    },
  );

  test(
    'a token of the same user can be revoked with a password, and only that one',
    { tag: ['@token'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder, true);
      const first = await rawLogin(repo, env.adminUsername, env.adminPassword);
      const second = await rawLogin(repo, env.adminUsername, env.adminPassword);
      expect(second.token).not.toBe(first.token);

      const revoked = await rawRequestPath(
        repo,
        'DELETE',
        `-/user/token/${first.token}`,
        npmAuthHeader(adminCredential()),
      );
      expect(revoked.status).toBe(200);

      expect(
        (await rawRequestPath(repo, 'GET', '-/whoami', { Authorization: `Bearer ${first.token}` }))
          .status,
      ).toBe(401);
      expect(
        (await rawRequestPath(repo, 'GET', '-/whoami', { Authorization: `Bearer ${second.token}` }))
          .status,
      ).toBe(200);
    },
  );

  test(
    'the secret of a deploy token is refused (403) and needs credentials (401)',
    { tag: ['@token', '@negative'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder, true);
      const deploy = await seeder.createToken(repo, { readOnly: true });
      const bearer = { Authorization: `Bearer ${deploy.token}` };

      const refused = await rawRequestPath(repo, 'DELETE', `-/user/token/${deploy.token}`, bearer);
      expect(refused.status, 'a deploy token is managed in the panel').toBe(403);
      expect(refused.msgId).toBe('deployTokenNotRevocable');
      // The npm error shape, so `npm logout` and `pnpm logout` print why (RPS-1391).
      const refusedBody = JSON.parse(refused.body.toString('utf8')) as { error?: string };
      expect(refusedBody.error).toContain('managed in the web UI');
      expect(refusedBody.error).toContain('npm logout');
      expect((await rawRequestPath(repo, 'GET', '-/whoami', bearer)).status).toBe(200);

      const anonymous = await rawRequestPath(repo, 'DELETE', '-/user/token/anything');
      expect(anonymous.status).toBe(401);

      const { token } = await rawLogin(repo, env.adminUsername, env.adminPassword);
      const unknown = await rawRequestPath(repo, 'DELETE', '-/user/token/not-a-token', {
        Authorization: `Bearer ${token}`,
      });
      expect(unknown.status, 'a value that is no login token').toBe(404);
    },
  );

  test(
    'the token of a deploy-token login is revoked, and the deploy token stays',
    { tag: ['@token'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder, true);
      const deploy = await seeder.createToken(repo, { readOnly: true });
      const { status, token } = await rawLogin(repo, 'typed-name', deploy.token);
      expect(status).toBe(201);
      const bearer = { Authorization: `Bearer ${token}` };
      expect((await rawRequestPath(repo, 'GET', '-/whoami', bearer)).status).toBe(200);

      expect((await rawRequestPath(repo, 'DELETE', `-/user/token/${token}`, bearer)).status).toBe(
        200,
      );

      expect((await rawRequestPath(repo, 'GET', '-/whoami', bearer)).status).toBe(401);
      expect(
        (
          await rawRequestPath(repo, 'GET', '-/whoami', {
            Authorization: `Bearer ${deploy.token}`,
          })
        ).status,
      ).toBe(200);
    },
  );
});

test.describe('npm dist-tag answers (raw HTTP)', () => {
  test(
    'the add and the remove answer ok, the package id and its tags (RPS-1362)',
    { tag: ['@dist-tags'] },
    async ({ seeder }) => {
      const repo = await newRepo(seeder, true);
      const name = `e2e-${seeder.runId}-tags`;
      await publish(repo, name, '1.0.0');
      await publish(repo, name, '2.0.0');
      const admin = { ...npmAuthHeader(adminCredential()), 'Content-Type': 'application/json' };

      const added = await rawRequestPath(
        repo,
        'PUT',
        `-/package/${name}/dist-tags/next`,
        admin,
        '"1.0.0"',
      );
      expect(added.status).toBe(200);
      expect(JSON.parse(added.body.toString('utf8'))).toEqual({
        ok: true,
        id: name,
        'dist-tags': { latest: '2.0.0', next: '1.0.0' },
      });

      const removed = await rawRequestPath(
        repo,
        'DELETE',
        `-/package/${name}/dist-tags/next`,
        admin,
      );
      expect(removed.status).toBe(200);
      expect(JSON.parse(removed.body.toString('utf8'))).toEqual({
        ok: true,
        id: name,
        'dist-tags': { latest: '2.0.0' },
      });
    },
  );
});
