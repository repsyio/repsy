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
 * RPS-1269: one canonical spelling of a repository type on the panel API. It is the upper-case enum
 * (`MAVEN`) everywhere the API writes a type, and a request may spell it in any case: the `type`
 * query of `GET /api/repos` and the `type` of the `POST /api/repos` body. The lower-case slug
 * (`maven`) is only for the UI's routes.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const ALL_TYPES = Object.values(RepoType);

test(
  '/format answers the upper-case type of a repository, for every type',
  { tag: ['@smoke'] },
  async ({ seeder, panelApi }) => {
    for (const type of ALL_TYPES) {
      const repo = await seeder.createRepo(type, { privateRepo: false });

      expect(await panelApi.getRepoFormat(repo.name)).toBe(type);
    }
  },
);

test(
  'the type of a new repository is read in any case and comes back in upper case',
  { tag: ['@smoke'] },
  async ({ seeder, panelApi }) => {
    for (const spelling of ['maven', 'Maven', 'mAvEn', 'MAVEN']) {
      const name = seeder.reserveRepoName(RepoType.MAVEN);

      const created = await panelApi.rawRequest('POST', '/api/repos', { name, type: spelling });
      seeder.adoptRepo(name);

      expect(created.status, spelling).toBe(200);
      expect(created.body.data).toMatchObject({ name, type: 'MAVEN' });
      expect(await panelApi.getRepoFormat(name)).toBe(RepoType.MAVEN);
    }
  },
);

test('a type that names no repository type is refused, in any case', async ({
  seeder,
  panelApi,
}) => {
  const name = seeder.reserveRepoName(RepoType.MAVEN);

  const created = await panelApi.rawRequest('POST', '/api/repos', { name, type: 'mvn' });

  expect(created.status).toBe(400);
  expect((await panelApi.listAllRepos({ q: name })).map((repo) => repo.name)).toEqual([]);
});

test('the type filter of the repository list is read in any case', async ({ seeder, panelApi }) => {
  const npm = await seeder.createRepo(RepoType.NPM, { privateRepo: false });
  const maven = await seeder.createRepo(RepoType.MAVEN, { privateRepo: false });

  for (const spelling of ['npm', 'Npm', 'NPM']) {
    const listed = await panelApi.rawRequest(
      'GET',
      `/api/repos?type=${spelling}&q=${encodeURIComponent(npm.name)}`,
    );

    expect(listed.status, spelling).toBe(200);
    const names = (listed.body.data as { content: { name: string; type: string }[] }).content;
    expect(
      names.map((repo) => repo.name),
      spelling,
    ).toEqual([npm.name]);
    expect(
      names.map((repo) => repo.type),
      spelling,
    ).toEqual(['NPM']);
  }

  const bogus = await panelApi.rawRequest('GET', `/api/repos?type=mvn&q=${maven.name}`);
  expect(bogus.status).toBe(400);
});
