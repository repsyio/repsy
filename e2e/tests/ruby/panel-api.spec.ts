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
 * RPS-1483: the panel API of Ruby (`/api/ruby/gems/...`), against what the REAL `gem push` stored. Each response is
 * validated against the response schema of `openapi-spec.yaml` (`src/api/spec-contract.ts`: the schema, and no
 * property the schema does not declare), and the values are matched to the client-side facts: the versions and
 * platforms the compact index `/info` lists, the checksum of the `.gem` file the client pushed (the panel's, the
 * index's and the file's own digest agree), the gemspec metadata (authors, description, homepage, required Ruby
 * version, the runtime and development dependencies) and the yank a real `gem yank` made. The delete operations are
 * judged by their effect on the wire: `/info` drops the version (or answers 404), the `.gem` answers 404 and a
 * real `bundle install` fails, while a sibling version still installs the bytes the client built.
 *
 * `gem install` is not the consumer here: it is broken by RPS-1233 and RPS-1234 (see `tests/ruby/publish-consume.spec.ts`),
 * so the consumer is `bundle install`, the adapter's own `resolve`.
 *
 * Copied from `tests/ruby/transitive-resolution.spec.ts` (gems with dependencies and platforms),
 * `src/clients/ruby-manage.ts` (`gem yank`) and `tests/pypi/panel-api.spec.ts` (the shared contract helpers). The
 * paging sweeps seed their rows over raw HTTP (`seedPackage`): the pages are the subject there, not the client.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import {
  callOperation,
  contractWorld,
  expectContract,
  expectCovers,
  expectFailure,
  expectPagingSweep,
} from '../../src/api/contract-checks.js';
import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import * as ruby from '../../src/clients/ruby.js';
import { gemEnv } from '../../src/clients/ruby.js';
import {
  adminCredential,
  type BuiltGem,
  buildGem,
  gemFilename,
  infoRelPath,
  parseInfo,
  rawDownload,
  rawGet,
  sha256Hex,
} from '../../src/clients/ruby-raw.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the Ruby panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = ['listGems', 'deleteGem', 'listGemVersions', 'getGemVersion', 'deleteGemVersion'];

interface Names {
  repoName: string;
  /** `e2e_<run>_panel`. */
  name: string;
}

interface VersionRow {
  version: string;
  platform: string;
  yanked: boolean;
  createdAt: string;
}

interface VersionDetail {
  id: string;
  version: string;
  platform: string;
  checksum: string;
  authors: string;
  description: string;
  homepage: string;
  requiredRubyVersion: string;
  yanked: boolean;
  createdAt: string;
  runtimeDependencies: { name: string; requirements: string; type: string }[];
  developmentDependencies: { name: string; requirements: string; type: string }[];
}

const worldOf = (names: Names, version: string, gem = names.name) =>
  contractWorld('ruby', names.repoName, gem, version, adminCredential());

async function newNames(seeder: Seeder): Promise<Names> {
  const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
  return { repoName: repo.name, name: `e2e_${seeder.runId}_panel` };
}

const values = (names: Names, version?: string): Record<string, string> => ({
  repoName: names.repoName,
  gemName: names.name,
  ...(version ? { version } : {}),
});

/** `<version>` for the plain platform, `<version>-<platform>` for the others: how `/info` names a line. */
const key = (version: string, platform: string): string =>
  platform === 'ruby' ? version : `${version}-${platform}`;

/** The versions `/info` lists (yanked ones are left out) with their checksum, or the status when it is not there. */
async function infoEntries(
  names: Names,
  gem = names.name,
): Promise<Record<string, string> | number> {
  const res = await rawGet(names.repoName, adminCredential(), infoRelPath(gem));
  return res.status === 200
    ? Object.fromEntries(
        parseInfo(res.body).map((line) => [key(line.version, line.platform), line.checksum]),
      )
    : res.status;
}

async function gemStatus(
  names: Names,
  version: string,
  platform = 'ruby',
  gem = names.name,
): Promise<number> {
  return (await rawDownload(names.repoName, adminCredential(), gemFilename(gem, version, platform)))
    .status;
}

async function panelVersions(names: Names): Promise<VersionRow[]> {
  const res = await callOperation('listGemVersions', values(names), { query: 'size=100' });
  return (expectContract('listGemVersions', res) as { content: VersionRow[] }).content;
}

/** Pushes a gem of our own build with the real `gem push`. */
async function pushGem(names: Names, built: BuiltGem): Promise<void> {
  const credential = adminCredential();
  const { home, work } = await isolatedWorkDir(`ruby-panel-push-${built.version}`);
  const file = path.join(work, built.filename);
  await fs.writeFile(file, built.bytes);
  const pushed = await run(
    'gem',
    ['push', file, '--host', `${env.repoBaseUrl}/${names.repoName}`],
    {
      cwd: work,
      env: gemEnv(home, credential),
      timeoutMs: 60_000,
      redact: [credential.password ?? ''],
      label: `ruby-panel-push-${built.version}`,
      input: '',
    },
  );
  expect(pushed.exitCode, `gem push ${built.filename}: ${pushed.command}`).toBe(0);
}

/** `gem yank` of one version (and platform) of the gem, with the real client. */
async function gemYank(names: Names, version: string, platform?: string): Promise<void> {
  const credential = adminCredential();
  const { home, work } = await isolatedWorkDir(`ruby-panel-yank-${version}`);
  const result = await run(
    'gem',
    [
      'yank',
      names.name,
      '-v',
      version,
      ...(platform === undefined ? [] : ['--platform', platform]),
      '--host',
      `${env.repoBaseUrl}/${names.repoName}`,
    ],
    {
      cwd: work,
      env: gemEnv(home, credential),
      timeoutMs: 60_000,
      redact: [credential.password ?? ''],
      label: 'ruby-panel-yank',
    },
  );
  expect(result.exitCode, `gem yank: ${result.command}`).toBe(0);
  expect(result.stdout, `gem yank: ${result.command}`).toContain('Successfully yanked gem');
}

test.describe('the Ruby panel API against what gem push stored', () => {
  test.setTimeout(300_000);

  test('names every operation of the Ruby panel API', () => {
    expectCovers(EXERCISED, '/api/ruby/');
  });

  test('lists, gets and describes what gem pushed and yanked, and the answers match the schema', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const plain = '1.0.0';
    const platformed = '1.1.0';
    const yanked = '2.0.0';
    const dependencies = [
      { name: 'rake', requirement: ['>= 1.0', '< 3'] },
      { name: 'rspec', requirement: '~> 3.0', type: 'development' as const },
    ];

    // The plain gem the adapter builds, two platform variants of one version with dependencies, and the gem to yank.
    const sums = new Map<string, string>();
    sums.set(
      key(plain, 'ruby'),
      (await ruby.seedPublish(worldOf(names, plain))).contentSha256 as string,
    );
    for (const platform of ['java', 'x86_64-linux']) {
      const built = await buildGem({
        name: names.name,
        version: platformed,
        platform,
        requiredRubyVersion: '>= 2.7',
        dependencies,
      });
      await pushGem(names, built);
      sums.set(key(platformed, platform), built.sha256Hex);
    }
    sums.set(
      key(yanked, 'ruby'),
      (await ruby.seedPublish(worldOf(names, yanked))).contentSha256 as string,
    );
    await gemYank(names, yanked);

    // The client-side facts: `/info` lists every version that is not yanked, with the digest of the pushed file.
    const listedByIndex = {
      [key(plain, 'ruby')]: sums.get(key(plain, 'ruby')),
      [key(platformed, 'java')]: sums.get(key(platformed, 'java')),
      [key(platformed, 'x86_64-linux')]: sums.get(key(platformed, 'x86_64-linux')),
    };
    expect(await infoEntries(names)).toEqual(listedByIndex);
    const bundled = await ruby.resolve(worldOf(names, plain));
    expect(bundled.clientExitCode, bundled.command).toBe(0);
    expect(bundled.contentSha256).toBe(sums.get(key(plain, 'ruby')));

    // GET /api/ruby/gems/{repo}: one row for the gem, its newest version that is not yanked as `latest`.
    const gems = expectContract(
      'listGems',
      await callOperation('listGems', { repoName: names.repoName }),
    ) as {
      content: { name: string; latest: string; updatedAt: string }[];
      page: { totalElements: number };
    };
    expect(gems.page.totalElements).toBe(1);
    expect(gems.content[0]).toMatchObject({ name: names.name, latest: platformed });
    expect(Date.parse(gems.content[0]?.updatedAt ?? ''), 'updatedAt').not.toBeNaN();

    // GET .../versions: every version and platform, the yank the client made shown on 2.0.0 only.
    const rows = await panelVersions(names);
    expect(
      Object.fromEntries(rows.map((row) => [key(row.version, row.platform), row.yanked])),
    ).toEqual({
      [key(plain, 'ruby')]: false,
      [key(platformed, 'java')]: false,
      [key(platformed, 'x86_64-linux')]: false,
      [key(yanked, 'ruby')]: true,
    });
    for (const row of rows) {
      expect(Date.parse(row.createdAt), `createdAt of ${row.version}`).not.toBeNaN();
    }

    // GET .../versions/{version}: the gemspec the client pushed, and the digest of the .gem file.
    const detailOf = async (version: string, platform: string): Promise<VersionDetail> =>
      expectContract(
        'getGemVersion',
        await callOperation('getGemVersion', values(names, version), {
          query: `platform=${platform}`,
        }),
      ) as VersionDetail;
    for (const platform of ['java', 'x86_64-linux']) {
      const detail = await detailOf(platformed, platform);
      expect(detail, platform).toMatchObject({
        version: platformed,
        platform,
        checksum: sums.get(key(platformed, platform)),
        authors: 'repsy-e2e',
        description: `e2e probe gem ${names.name}@${platformed}`,
        homepage: `https://example.com/${names.name}`,
        requiredRubyVersion: '>= 2.7',
        yanked: false,
        runtimeDependencies: [{ name: 'rake', requirements: '>= 1.0, < 3', type: 'runtime' }],
        developmentDependencies: [{ name: 'rspec', requirements: '~> 3.0', type: 'development' }],
      });
      expect(Date.parse(detail.createdAt)).not.toBeNaN();
    }
    // With no `platform` the plain (ruby) gem is meant, so a version that exists only for other platforms is not found.
    const plainDetail = expectContract(
      'getGemVersion',
      await callOperation('getGemVersion', values(names, plain)),
    ) as VersionDetail;
    expect(plainDetail).toMatchObject({
      version: plain,
      platform: 'ruby',
      checksum: sums.get(key(plain, 'ruby')),
      requiredRubyVersion: '>= 0',
      runtimeDependencies: [],
      developmentDependencies: [],
    });
    expectFailure(
      'getGemVersion',
      await callOperation('getGemVersion', values(names, platformed)),
      404,
      'gemVersionNotFound',
    );
    // The digest the panel shows is the digest of the file the wire serves and of the checksum `/info` advertises.
    for (const [version, platform] of [
      [plain, 'ruby'],
      [platformed, 'java'],
      [platformed, 'x86_64-linux'],
    ] as const) {
      const served = await rawDownload(
        names.repoName,
        adminCredential(),
        gemFilename(names.name, version, platform),
      );
      expect(served.status, key(version, platform)).toBe(200);
      expect(sha256Hex(served.body), key(version, platform)).toBe(sums.get(key(version, platform)));
    }
    const yankedDetail = await detailOf(yanked, 'ruby');
    expect(yankedDetail, 'a yanked version is still described').toMatchObject({
      version: yanked,
      yanked: true,
      checksum: sums.get(key(yanked, 'ruby')),
    });
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    await ruby.seedPublish(worldOf(names, '1.0.0'));
    const missing = { ...values(names), gemName: 'e2e_no_such_gem' };

    expectFailure(
      'listGems',
      await callOperation('listGems', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'listGems',
      await callOperation('listGems', { repoName: names.repoName }, { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'listGemVersions',
      await callOperation('listGemVersions', missing),
      404,
      'gemNotFound',
    );
    expectFailure(
      'getGemVersion',
      await callOperation('getGemVersion', values(names, '9.9.9')),
      404,
      'gemVersionNotFound',
    );
    expectFailure(
      'getGemVersion',
      await callOperation('getGemVersion', values(names, '1.0.0'), { query: 'platform=java' }),
      404,
      'gemVersionNotFound',
    );
    expectFailure(
      'deleteGemVersion',
      await callOperation('deleteGemVersion', values(names, '9.9.9')),
      404,
      'gemVersionNotFound',
    );
    expectFailure('deleteGem', await callOperation('deleteGem', missing), 404, 'gemNotFound');

    // Every failure above left the gem where it was.
    expect((await panelVersions(names)).map((row) => row.version)).toEqual(['1.0.0']);
    expect(Object.keys((await infoEntries(names)) as Record<string, string>)).toEqual(['1.0.0']);
  });

  test('pages, sorts and narrows the gem and version lists, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const repo = { name: names.repoName, type: RepoType.RUBY };
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage(repo, seeder, { index });
    }
    await expectPagingSweep<{ name: string }>({
      operationId: 'listGems',
      values: { repoName: names.repoName },
      total: 5,
      keyOf: (row) => row.name,
      sorts: [{ property: 'name', value: (row) => row.name }],
    });
    const narrowed = expectContract(
      'listGems',
      await callOperation('listGems', { repoName: names.repoName }, { query: 'q=pkg_3' }),
    ) as { content: { name: string }[] };
    expect(narrowed.content).toHaveLength(1);
    expect(narrowed.content[0]?.name).toContain('_pkg_3');

    // The versions of one gem, the second list operation with paging.
    for (const version of ['1.0.1', '1.0.2', '1.0.3', '1.0.4', '1.0.5']) {
      await seedPackage(repo, seeder, { name: names.name, version });
    }
    await expectPagingSweep<{ version: string }>({
      operationId: 'listGemVersions',
      values: values(names),
      total: 5,
      keyOf: (row) => row.version,
      sorts: [{ property: 'version', value: (row) => row.version }],
    });
    const oneVersion = expectContract(
      'listGemVersions',
      await callOperation('listGemVersions', values(names), { query: 'q=1.0.3' }),
    ) as { content: { version: string }[] };
    expect(oneVersion.content.map((row) => row.version)).toEqual(['1.0.3']);
  });

  test('deleting a version, a platform variant, then the gem, removes them from the wire and from bundle install', async ({
    seeder,
  }) => {
    const names = await newNames(seeder);
    const removed = '1.0.0';
    const kept = '1.1.0';
    const seeds = new Map<string, string>();
    for (const version of [removed, kept]) {
      seeds.set(version, (await ruby.seedPublish(worldOf(names, version))).contentSha256 as string);
    }
    // 1.1.0 also exists for java: the platform is part of what a delete names.
    const java = await buildGem({ name: names.name, version: kept, platform: 'java' });
    await pushGem(names, java);
    expect(Object.keys((await infoEntries(names)) as Record<string, string>).sort()).toEqual(
      [removed, kept, key(kept, 'java')].sort(),
    );
    expect(await gemStatus(names, removed)).toBe(200);

    expectContract(
      'deleteGemVersion',
      await callOperation('deleteGemVersion', values(names, removed)),
    );

    expect(
      (await panelVersions(names)).map((row) => key(row.version, row.platform)).sort(),
    ).toEqual([kept, key(kept, 'java')].sort());
    expectFailure(
      'getGemVersion',
      await callOperation('getGemVersion', values(names, removed)),
      404,
      'gemVersionNotFound',
    );
    expect(await infoEntries(names), 'the compact index drops the version').toEqual({
      [kept]: seeds.get(kept),
      [key(kept, 'java')]: java.sha256Hex,
    });
    expect(await gemStatus(names, removed)).toBe(404);
    expect(await gemStatus(names, kept)).toBe(200);
    const gone = await ruby.resolve(worldOf(names, removed));
    expect(gone.clientExitCode, `bundle install of the deleted version: ${gone.command}`).not.toBe(
      0,
    );
    const resolved = await ruby.resolve(worldOf(names, kept));
    expect(resolved.clientExitCode, resolved.command).toBe(0);
    expect(resolved.contentSha256).toBe(seeds.get(kept));
    expectFailure(
      'deleteGemVersion',
      await callOperation('deleteGemVersion', values(names, removed)),
      404,
      'gemVersionNotFound',
    );

    // The java variant of 1.1.0: only that platform goes, the plain 1.1.0 stays.
    expectContract(
      'deleteGemVersion',
      await callOperation('deleteGemVersion', values(names, kept), { query: 'platform=java' }),
    );
    expect(await infoEntries(names)).toEqual({ [kept]: seeds.get(kept) });
    expect(await gemStatus(names, kept, 'java')).toBe(404);
    expect(await gemStatus(names, kept)).toBe(200);
    expectFailure(
      'getGemVersion',
      await callOperation('getGemVersion', values(names, kept), { query: 'platform=java' }),
      404,
      'gemVersionNotFound',
    );
    expect((await ruby.resolve(worldOf(names, kept))).contentSha256).toBe(seeds.get(kept));

    // The last version.
    expectContract(
      'deleteGemVersion',
      await callOperation('deleteGemVersion', values(names, kept)),
    );
    expect(await infoEntries(names)).toBe(404);
    expect(await gemStatus(names, kept)).toBe(404);

    // Now the whole gem, on a fresh one with two versions.
    const whole = { ...names, name: `${names.name}_whole` };
    for (const version of ['1.0.0', '2.0.0']) {
      await ruby.seedPublish(worldOf(whole, version, whole.name));
    }
    expect(Object.keys((await infoEntries(whole)) as Record<string, string>).sort()).toEqual([
      '1.0.0',
      '2.0.0',
    ]);
    expectContract('deleteGem', await callOperation('deleteGem', values(whole)));
    expect(await infoEntries(whole)).toBe(404);
    expect(await gemStatus(whole, '2.0.0')).toBe(404);
    const lost = await ruby.resolve(worldOf(whole, '2.0.0', whole.name));
    expect(lost.clientExitCode, `bundle install of the deleted gem: ${lost.command}`).not.toBe(0);
    expectFailure(
      'listGemVersions',
      await callOperation('listGemVersions', values(whole)),
      404,
      'gemNotFound',
    );
    const rows = expectContract(
      'listGems',
      await callOperation('listGems', { repoName: names.repoName }),
    ) as { content: unknown[] };
    expect(rows.content).toEqual([]);
    expectFailure('deleteGem', await callOperation('deleteGem', values(whole)), 404, 'gemNotFound');
  });
});
