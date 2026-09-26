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
 * RPS-1483: what the Maven file browser of the panel stands on, two operations of the panel API:
 *
 *  - `GET /api/repos/{repoName}/contents?path=` lists one directory of a Maven repo (name, size,
 *    directory flag), for the browser's table;
 *  - `POST /api/repos/{repoName}/download-token?path=` issues a one-minute token that the download link
 *    carries as `?downloadToken=` (a navigation cannot set an `Authorization` header). It opens ONE path
 *    of ONE repo for reading, on the protocol port only: the panel API and the protocol port both refuse
 *    it as a Bearer, and it never writes.
 *
 * The repos are private, so a token is the only thing that lets an anonymous request through. The UI
 * half (the browser page and its download button) is `tests/ui/packages/maven.spec.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { adminBearer, apiUrl, edgeRequest, repoUrl } from '../../src/clients/edge-raw.js';
import {
  adminCredential,
  artifactDir,
  rawGet,
  repoTree,
  sha256Hex,
  splitPackageName,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { type SeededPackage, seedPackage } from '../../src/seed/packages.js';
import type { SeededRepo, Seeder } from '../../src/seed/seeder.js';

interface StorageItem {
  name: string;
  directory: boolean;
  size: number | null;
}

interface Setup {
  repo: SeededRepo;
  other: SeededRepo;
  pkg: SeededPackage;
  bearer: Record<string, string>;
  /** Repo-relative, no leading slash. */
  dir: string;
  jarPath: string;
  pomPath: string;
}

/** Two private Maven repos holding the very same file, so a token can be tried on the wrong one. */
async function setUp(seeder: Seeder): Promise<Setup> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const other = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  const pkg = await seedPackage(repo, seeder, {});
  await seedPackage(other, seeder, { name: pkg.name });
  const [groupId, artifactId] = splitPackageName(pkg.name);
  const dir = versionDir(groupId, artifactId, pkg.version);
  return {
    repo,
    other,
    pkg,
    bearer: { Authorization: `Bearer ${await adminBearer()}` },
    dir,
    jarPath: `${dir}/${artifactId}-${pkg.version}.jar`,
    pomPath: `${dir}/${artifactId}-${pkg.version}.pom`,
  };
}

async function contents(
  setup: Setup,
  path: string,
  headers: Record<string, string> = setup.bearer,
) {
  return edgeRequest(
    apiUrl(`/api/repos/${setup.repo.name}/contents?path=${encodeURIComponent(path)}`),
    {
      headers,
    },
  );
}

async function downloadToken(
  setup: Setup,
  path: string,
  repoName = setup.repo.name,
): Promise<string> {
  const res = await edgeRequest(
    apiUrl(`/api/repos/${repoName}/download-token?path=${encodeURIComponent(path)}`),
    { method: 'POST', headers: setup.bearer },
  );
  expect(res.status, res.text).toBe(200);
  const token = (res.json as { data?: string }).data;
  expect(token).toBeTruthy();
  return token as string;
}

const wire = (repoName: string, path: string, token: string, init: { method?: string } = {}) =>
  edgeRequest(repoUrl(`/${repoName}/${path}?downloadToken=${encodeURIComponent(token)}`), init);

test.describe('the contents of a Maven repo', { tag: ['@smoke'] }, () => {
  test('lists the directories and files the deploy stored, with their sizes', async ({
    seeder,
  }) => {
    const setup = await setUp(seeder);
    const [groupId, artifactId] = splitPackageName(setup.pkg.name);

    const root = await contents(setup, '');
    expect(root.status).toBe(200);
    const rootItems = (root.json as { data: StorageItem[] }).data;
    expect(rootItems.find((item) => item.name === `${groupId.split('.')[0]}/`)).toMatchObject({
      directory: true,
    });

    const artifact = (await contents(setup, artifactDir(groupId, artifactId))).json as {
      data: StorageItem[];
    };
    expect(artifact.data.map((item) => item.name)).toEqual(
      expect.arrayContaining([`${setup.pkg.version}/`, 'maven-metadata.xml']),
    );

    const version = (await contents(setup, setup.dir)).json as { data: StorageItem[] };
    const byName = new Map(version.data.map((item) => [item.name, item]));
    for (const path of [setup.jarPath, setup.pomPath]) {
      const file = path.slice(path.lastIndexOf('/') + 1);
      const stored = await rawGet(setup.repo.name, adminCredential(), path);
      expect(stored.status).toBe(200);
      expect(byName.get(file), file).toMatchObject({ directory: false, size: stored.body.length });
    }
  });

  test('refuses a path that leaves the repo, and answers 404 for one that is not there', async ({
    seeder,
  }) => {
    const setup = await setUp(seeder);

    const escaped = await contents(setup, '../x');
    expect(escaped.status).toBe(400);
    expect(escaped.json).toMatchObject({ msgId: 'invalidStoragePath' });

    const missing = await contents(setup, 'no/such/dir');
    expect(missing.status).toBe(404);
    expect(missing.json).toMatchObject({ msgId: 'resourceNotFound' });
  });

  test('is not readable anonymously on a private repo', async ({ seeder }) => {
    const setup = await setUp(seeder);

    const res = await contents(setup, '', {});

    expect(res.status).toBe(401);
    expect(res.json).toMatchObject({ msgId: 'loginRequired' });
  });
});

test.describe('the download token', { tag: ['@smoke'] }, () => {
  test('opens its path on the protocol port, to a caller with no credentials at all', async ({
    seeder,
  }) => {
    const setup = await setUp(seeder);
    const token = await downloadToken(setup, setup.jarPath);

    const anonymous = await edgeRequest(repoUrl(`/${setup.repo.name}/${setup.jarPath}`));
    expect(anonymous.status).toBe(401);

    const res = await fetch(
      repoUrl(`/${setup.repo.name}/${setup.jarPath}?downloadToken=${encodeURIComponent(token)}`),
    );
    const bytes = Buffer.from(await res.arrayBuffer());
    const stored = await rawGet(setup.repo.name, adminCredential(), setup.jarPath);

    expect(res.status).toBe(200);
    expect(sha256Hex(bytes)).toBe(sha256Hex(stored.body));
  });

  test('is refused without a session, and for a path that leaves the repo', async ({ seeder }) => {
    const setup = await setUp(seeder);

    const anonymous = await edgeRequest(
      apiUrl(
        `/api/repos/${setup.repo.name}/download-token?path=${encodeURIComponent(setup.jarPath)}`,
      ),
      { method: 'POST' },
    );
    expect(anonymous.status).toBe(401);

    const escaped = await edgeRequest(
      apiUrl(`/api/repos/${setup.repo.name}/download-token?path=${encodeURIComponent('../x')}`),
      { method: 'POST', headers: setup.bearer },
    );
    expect(escaped.status).toBe(400);
    expect(escaped.json).toMatchObject({ msgId: 'invalidStoragePath' });
  });

  test('is refused for any other path of the same repo', async ({ seeder }) => {
    const setup = await setUp(seeder);
    const token = await downloadToken(setup, setup.jarPath);

    for (const path of [setup.pomPath, `${setup.dir}/`, setup.dir]) {
      const res = await wire(setup.repo.name, path, token);

      expect(res.status, path).toBe(401);
      expect(res.json, path).toMatchObject({ msgId: 'accessNotAllowed' });
    }
  });

  test('is refused for the very same path of another repo', async ({ seeder }) => {
    const setup = await setUp(seeder);
    const token = await downloadToken(setup, setup.jarPath);

    const res = await wire(setup.other.name, setup.jarPath, token);

    expect(res.status).toBe(401);
    expect(res.json).toMatchObject({ msgId: 'accessNotAllowed' });
  });

  test('is refused when it is not a token at all', async ({ seeder }) => {
    const setup = await setUp(seeder);

    const res = await wire(setup.repo.name, setup.jarPath, 'not-a-token');

    expect(res.status).toBe(401);
  });

  test('never writes: the same path answers a PUT with 401 and the repo is unchanged', async ({
    seeder,
  }) => {
    const setup = await setUp(seeder);
    const token = await downloadToken(setup, setup.jarPath);
    const before = await repoTree(setup.repo.name);

    const overwrite = await edgeRequest(
      repoUrl(`/${setup.repo.name}/${setup.jarPath}?downloadToken=${encodeURIComponent(token)}`),
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/octet-stream' },
        body: 'overwritten',
      },
    );

    expect(overwrite.status).toBe(401);
    expect(await repoTree(setup.repo.name)).toEqual(before);
  });

  test('is not a session: refused as a Bearer on the panel API and on the protocol port', async ({
    seeder,
  }) => {
    const setup = await setUp(seeder);
    const token = await downloadToken(setup, setup.jarPath);
    const asBearer = { Authorization: `Bearer ${token}` };

    for (const path of [
      '/api/profile',
      `/api/repos/${setup.repo.name}/contents?path=${encodeURIComponent(setup.dir)}`,
      `/api/mvn/artifacts/${setup.repo.name}`,
    ]) {
      const res = await edgeRequest(apiUrl(path), { headers: asBearer });

      expect(res.status, path).toBe(401);
    }

    const onWire = await edgeRequest(repoUrl(`/${setup.repo.name}/${setup.jarPath}`), {
      headers: asBearer,
    });
    expect(onWire.status).toBe(401);
  });

  test('is not honoured on the panel API as a query parameter either', async ({ seeder }) => {
    const setup = await setUp(seeder);
    const token = await downloadToken(setup, setup.jarPath);

    const res = await edgeRequest(
      apiUrl(
        `/api/repos/${setup.repo.name}/contents?path=${encodeURIComponent(setup.dir)}&downloadToken=${encodeURIComponent(token)}`,
      ),
    );

    expect(res.status).toBe(401);
  });

  test('expires after a minute', { tag: ['@slow'] }, async ({ seeder }) => {
    test.setTimeout(120_000);
    const setup = await setUp(seeder);
    const token = await downloadToken(setup, setup.jarPath);
    expect((await wire(setup.repo.name, setup.jarPath, token)).status).toBe(200);

    await new Promise((resolve) => setTimeout(resolve, 61_000));

    const expired = await wire(setup.repo.name, setup.jarPath, token);
    expect(expired.status).toBe(401);
    expect(expired.json).toMatchObject({ msgId: 'downloadTokenExpired' });
  });
});
