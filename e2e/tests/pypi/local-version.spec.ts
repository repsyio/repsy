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
 * RPS-1662: a PEP 440 local version (`1.0.0+local.1`, `2.1.0+cu118`) is accepted on upload and KEPT, so
 * `1.0.0` and each `1.0.0+<label>` are separate releases. Every case runs a real client: `twine`
 * and `uv publish` upload the local builds, `pip` and `uv pip` install them, and the panel API
 * (whose paths carry the `+` as `%2B`) lists, reads and deletes them.
 *
 * Probed live while writing this file (pip 26, uv 0.12): both clients request the file at the href the
 * project page prints, with a literal `+`, and accept it.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import {
  callOperation,
  expectContract,
  expectFailure,
} from '../../src/api/contract-checks.js';
import { RepoType } from '../../src/api/panel-api.js';
import * as pypi from '../../src/clients/pypi.js';
import { pipEnv } from '../../src/clients/pypi.js';
import {
  adminCredential,
  buildWheel,
  indexUrlFor,
  msgIdOf,
  parseSimplePage,
  rawDownload,
  rawGetSimplePage,
  rawUpload,
  sha256Hex,
  uploadUrl,
  wheelFilename,
} from '../../src/clients/pypi-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { runUv } from '../../src/clients/uv.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const PUBLIC = '1.0.0';
const LOCAL_1 = '1.0.0+local.1';
const LOCAL_2 = '1.0.0+local.2';

interface Layout {
  repoName: string;
  packageName: string;
}

async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
  return { repoName: repo.name, packageName: `e2e-${seeder.runId}-${label}` };
}

/** Uploads one wheel over raw HTTP (the bytes are known, so a download can be compared). */
async function seedRaw(layout: Layout, version: string) {
  const built = buildWheel({ name: layout.packageName, version, marker: `lv ${version}` });
  const res = await rawUpload(layout.repoName, adminCredential(), built);
  expect(res.status, `upload ${built.filename}: ${msgIdOf(res.body) ?? ''}`).toBe(200);
  return built;
}

async function pipDownload(layout: Layout, requirement: string, label: string) {
  const { home, work } = await isolatedWorkDir(`pypi-lv-${label}`);
  const dest = path.join(work, 'dl');
  await fs.mkdir(dest, { recursive: true });
  const res = await run(
    'python3',
    [
      '-m',
      'pip',
      'download',
      '--no-deps',
      '--only-binary=:all:',
      '--no-cache-dir',
      '--dest',
      dest,
      requirement,
    ],
    {
      cwd: work,
      env: pipEnv(home, adminCredential(), layout.repoName),
      timeoutMs: 120_000,
      label: `pypi-lv-${label}`,
    },
  );
  const files = res.exitCode === 0 ? await fs.readdir(dest) : [];
  return { res, files, dest };
}

async function projectFiles(layout: Layout): Promise<string[]> {
  const page = await rawGetSimplePage(layout.repoName, adminCredential(), layout.packageName);
  expect(page.status, 'the project page').toBe(200);
  return parseSimplePage(page.body)
    .map((link) => link.filename)
    .sort();
}

test.describe('pypi local versions (PEP 440 +local)', () => {
  test.setTimeout(300_000);

  test(
    'twine uploads a local-version wheel and pip installs exactly that build by ==1.0.0+local.1',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'twine');
      const plain = await seedRaw(layout, PUBLIC);

      const published = await pypi.publish({
        scenario: {
          id: 'local-version',
          tags: [],
          repo: { privateRepo: true },
          credential: 'admin-password',
          expect: { publish: 'ok', consume: 'ok' },
        },
        protocol: 'pypi',
        repoName: layout.repoName,
        credential: adminCredential(),
        publishTarget: { packageName: layout.packageName, version: LOCAL_1 },
        consumeTarget: { packageName: layout.packageName, version: LOCAL_1 },
      });
      expect(published.outcome, `twine: ${published.command}`).toBe('ok');

      const files = await projectFiles(layout);
      expect(files, 'the page lists both builds').toEqual(
        [wheelFilename(layout.packageName, PUBLIC), wheelFilename(layout.packageName, LOCAL_1)].sort(),
      );

      const local = await pipDownload(layout, `${layout.packageName}==${LOCAL_1}`, 'exact');
      expect(local.res.exitCode, local.res.command).toBe(0);
      expect(local.files).toEqual([wheelFilename(layout.packageName, LOCAL_1)]);

      // The public build still downloads and installs by its own exact version.
      const dl = await rawDownload(
        layout.repoName,
        adminCredential(),
        layout.packageName,
        wheelFilename(layout.packageName, PUBLIC),
      );
      expect(sha256Hex(dl.body), 'the public build is untouched').toBe(plain.sha256Hex);
    },
  );

  test(
    'a release is its own exact version: local builds sort after the public release and a ' +
      'public-only pin is answered by PEP 440, not by prefix',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'pin');
      const plain = await seedRaw(layout, PUBLIC);
      const local = await seedRaw(layout, LOCAL_1);

      // PEP 440: `==1.0.0` ignores the local label of a candidate, and pip prefers the highest
      // matching version, which is the local build. With the local build as the ONLY 1.0.0 it is the one
      // installed too. Both facts are pip's, the registry only has to serve the two files.
      const pinned = await pipDownload(layout, `${layout.packageName}==${PUBLIC}`, 'public-pin');
      expect(pinned.res.exitCode, pinned.res.command).toBe(0);
      expect(pinned.files).toHaveLength(1);
      const got = await fs.readFile(path.join(pinned.dest, pinned.files[0] as string));
      expect([plain.sha256Hex, local.sha256Hex], 'one of the two builds').toContain(sha256Hex(got));

      // The exact local pin never returns the public build.
      const exact = await pipDownload(layout, `${layout.packageName}===${PUBLIC}`, 'triple-eq');
      expect(exact.res.exitCode, exact.res.command).toBe(0);
      expect(exact.files).toEqual([wheelFilename(layout.packageName, PUBLIC)]);
    },
  );

  test(
    'uv publish uploads a local build and uv pip install installs it',
    { tag: ['@uv', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'uv');
      const { home, work } = await isolatedWorkDir('pypi-lv-uv');
      const credential = adminCredential();
      const built = buildWheel({ name: layout.packageName, version: LOCAL_2, marker: 'uv local' });
      await fs.mkdir(path.join(work, 'dist'), { recursive: true });
      await fs.writeFile(path.join(work, 'dist', built.filename), built.bytes);

      const published = await runUv(
        'pypi-lv-uv-publish',
        [
          'publish',
          '--trusted-publishing',
          'never',
          '--publish-url',
          uploadUrl(layout.repoName),
          path.join('dist', built.filename),
        ],
        { home, cwd: work, credential, mode: 'publish' },
      );
      expect(published.exitCode, published.command).toBe(0);

      expect((await runUv('pypi-lv-uv-venv', ['venv', 'venv'], { home, cwd: work, credential, mode: 'none' })).exitCode).toBe(0);
      const install = await runUv(
        'pypi-lv-uv-install',
        [
          'pip',
          'install',
          '--python',
          'venv/bin/python',
          '--index-url',
          indexUrlFor(layout.repoName, credential),
          `${layout.packageName}==${LOCAL_2}`,
        ],
        { home, cwd: work, credential, mode: 'none' },
      );
      expect(install.exitCode, install.command).toBe(0);
      const dl = await rawDownload(layout.repoName, credential, layout.packageName, built.filename);
      expect(sha256Hex(dl.body)).toBe(built.sha256Hex);
    },
  );

  test(
    'the panel lists the builds in PEP 440 order, describes a + version, and deleting 1.0.0 ' +
      'leaves the local builds (files and rows) alone',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'panel');
      await seedRaw(layout, PUBLIC);
      await seedRaw(layout, LOCAL_1);
      await seedRaw(layout, LOCAL_2);
      const values = (version?: string): Record<string, string> => ({
        repoName: layout.repoName,
        packageName: layout.packageName,
        ...(version ? { version } : {}),
      });
      const versions = async (): Promise<string[]> => {
        const page = expectContract(
          'listPypiReleases',
          await callOperation('listPypiReleases', values()),
        ) as { content: { version: string }[] };
        return page.content.map((row) => row.version);
      };

      expect(await versions(), 'newest first: a local build outranks its public release').toEqual([
        LOCAL_2,
        LOCAL_1,
        PUBLIC,
      ]);
      const detail = expectContract(
        'getPypiRelease',
        await callOperation('getPypiRelease', values(LOCAL_1)),
      ) as { version: string };
      expect(detail.version).toBe(LOCAL_1);

      expectContract('deletePypiRelease', await callOperation('deletePypiRelease', values(PUBLIC)));

      expect(await versions()).toEqual([LOCAL_2, LOCAL_1]);
      expect(await projectFiles(layout), 'only the public file went').toEqual(
        [wheelFilename(layout.packageName, LOCAL_1), wheelFilename(layout.packageName, LOCAL_2)].sort(),
      );
      for (const version of [LOCAL_1, LOCAL_2]) {
        const dl = await rawDownload(
          layout.repoName,
          adminCredential(),
          layout.packageName,
          wheelFilename(layout.packageName, version),
        );
        expect(dl.status, `${version} still downloads`).toBe(200);
      }

      // And the other way round: deleting a local build leaves the next one.
      expectContract('deletePypiRelease', await callOperation('deletePypiRelease', values(LOCAL_2)));
      expect(await versions()).toEqual([LOCAL_1]);
      expectFailure(
        'getPypiRelease',
        await callOperation('getPypiRelease', values(LOCAL_2)),
        404,
        'releaseNotFound',
      );
    },
  );
});
