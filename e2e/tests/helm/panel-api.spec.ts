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
 * RPS-1483: the panel API of Helm (`/api/helm/charts/...`, all 6 operations), against what the REAL `helm`
 * published, over both protocols one repo serves: OCI (`helm push`) and classic ChartMuseum (`helm cm-push`).
 * Each response is validated against the response schema of `openapi-spec.yaml` (`src/api/spec-contract.ts`: the
 * schema, and no property the schema does not declare), and the values are matched to the client-side facts:
 * `helm show chart` (name, version, appVersion, type, description), the `.tgz` `helm package` built (digest and
 * size, for the OCI chart layer, which `helm push` sends verbatim), the digest a `helm push` prints for the
 * manifest, and the `index.yaml` entry of the classic view (digest, appVersion, type, created). A classic chart
 * is stored REPACKAGED by `cm-push` (see `src/clients/helm-classic.ts`), so its digest and size are those of
 * the file the classic download serves.
 *
 * The delete operations are judged by their effect on the wire: `index.yaml` drops the version, the classic
 * download and the OCI manifest answer 404, a real `helm pull` and `helm show chart` fail, and a sibling version
 * still pulls the exact bytes.
 *
 * Copied from `tests/helm/classic-publish-consume.spec.ts` (the real helm/cm-push calls and the index reads)
 * and `tests/pypi/panel-api.spec.ts` (the shared contract helpers). The paging sweep seeds its rows over raw
 * HTTP (`seedPackage`): the pages are the subject there, not the client.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { parse as parseYaml } from 'yaml';

import {
  callOperation,
  expectContract,
  expectCovers,
  expectFailure,
  expectPagingSweep,
} from '../../src/api/contract-checks.js';
import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { helmEnv, plainHttpFlag, renderHelmRegistryConfig } from '../../src/clients/helm.js';
import {
  adminCredential,
  chartFileName,
  classicRepoUrl,
  indexEntry,
  ociChartRef,
  ociRepoRef,
  parseIndex,
  rawDownloadChart,
  rawGetIndex,
  rawGetManifest,
  type IndexEntry,
} from '../../src/clients/helm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { seedPackage } from '../../src/seed/packages.js';
import type { Seeder } from '../../src/seed/seeder.js';

/** Every operation of the Helm panel API this spec calls; a route the spec gains must be added (or the check below fails). */
const EXERCISED = [
  'searchHelmCharts',
  'getHelmChartVersions',
  'getHelmChartDetail',
  'getHelmChartOciTags',
  'deleteHelmChartVersion',
  'deleteAllHelmChartVersions',
];

// A function, not a constant: a spec file is imported by `playwright test --list` too, and a Repsy Cloud
// consumer lists it without the owner's password (RPS-1500), which `adminCredential()` reads.
const classicCredentials = (): string[] => {
  const admin = adminCredential();
  return ['--username', admin.username ?? '', '--password', admin.password ?? ''];
};

const sha256 = (bytes: Buffer): string =>
  `sha256:${createHash('sha256').update(bytes).digest('hex')}`;

interface ChartFacts {
  name: string;
  version: string;
  /** `Chart.yaml` lines after `name`/`version`/`description`. */
  extra: string;
  tgz: Buffer;
  tgzFile: string;
  appVersion?: string;
  type?: string;
}

interface Session {
  repoName: string;
  work: string;
  helm: (args: string[], label: string) => ReturnType<typeof run>;
}

async function newSession(seeder: Seeder, label: string): Promise<Session> {
  const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
  const { home, work } = await isolatedWorkDir(`${label}-${seeder.runId}`);
  await renderHelmRegistryConfig(home, adminCredential());
  return {
    repoName: repo.name,
    work,
    helm: (args, runLabel) =>
      run('helm', args, {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        label: runLabel,
        redact: [adminCredential().password ?? ''],
      }),
  };
}

async function helmOk(session: Session, args: string[], label: string): Promise<string> {
  const result = await session.helm(args, label);
  expect(result.exitCode, `${result.command}: ${result.stderr}`).toBe(0);
  return result.stdout;
}

/** `helm package` of a chart directory written here: the real client builds the `.tgz`. */
async function packageChart(
  session: Session,
  chart: { name: string; version: string; appVersion?: string; type?: string },
): Promise<ChartFacts> {
  const dir = path.join(session.work, `${chart.name}-${chart.version}-src`, chart.name);
  await fs.mkdir(dir, { recursive: true });
  const extra = [
    chart.appVersion ? `appVersion: "${chart.appVersion}"` : '',
    chart.type ? `type: ${chart.type}` : '',
  ]
    .filter(Boolean)
    .join('\n');
  await fs.writeFile(
    path.join(dir, 'Chart.yaml'),
    `apiVersion: v2\nname: ${chart.name}\nversion: ${chart.version}\ndescription: panel contract ${chart.name}\n${extra}\n`,
  );
  await fs.writeFile(path.join(dir, 'values.yaml'), `marker: ${chart.name}-${chart.version}\n`);
  const out = path.join(session.work, 'pkgs');
  await fs.mkdir(out, { recursive: true });
  await helmOk(session, ['package', dir, '--destination', out], 'panel-helm-package');
  const tgzFile = path.join(out, chartFileName(chart.name, chart.version));
  return { ...chart, extra, tgz: await fs.readFile(tgzFile), tgzFile };
}

/** `helm push` over OCI: the manifest digest the client prints. */
async function pushOci(session: Session, chart: ChartFacts): Promise<string> {
  const stdout = await helmOk(
    session,
    ['push', chart.tgzFile, ociRepoRef(session.repoName), ...plainHttpFlag()],
    'panel-helm-push',
  );
  const digest = /Digest: (sha256:[0-9a-f]{64})/.exec(stdout)?.[1];
  expect(digest, `helm push prints the manifest digest: ${stdout}`).toBeDefined();
  return digest as string;
}

async function pushClassic(session: Session, chart: ChartFacts): Promise<void> {
  await helmOk(
    session,
    ['cm-push', chart.tgzFile, classicRepoUrl(session.repoName), ...classicCredentials()],
    'panel-helm-cm-push',
  );
}

/** `helm show chart` over OCI (`variant: 'oci'`) or the classic repo, parsed. */
async function showChart(
  session: Session,
  variant: 'oci' | 'classic',
  name: string,
  version: string,
): Promise<Record<string, unknown>> {
  const args =
    variant === 'oci'
      ? [
          'show',
          'chart',
          ociChartRef(session.repoName, name),
          '--version',
          version,
          ...plainHttpFlag(),
        ]
      : [
          'show',
          'chart',
          '--repo',
          classicRepoUrl(session.repoName),
          name,
          '--version',
          version,
          ...classicCredentials(),
        ];
  return parseYaml(await helmOk(session, args, `panel-helm-show-${variant}`)) as Record<
    string,
    unknown
  >;
}

async function pullExitCode(
  session: Session,
  variant: 'oci' | 'classic',
  name: string,
  version: string,
): Promise<number> {
  const dest = path.join(session.work, `pull-${variant}-${name}-${version}`);
  await fs.mkdir(dest, { recursive: true });
  const args =
    variant === 'oci'
      ? ['pull', ociChartRef(session.repoName, name), '--version', version, ...plainHttpFlag()]
      : [
          'pull',
          '--repo',
          classicRepoUrl(session.repoName),
          name,
          '--version',
          version,
          ...classicCredentials(),
        ];
  return (await session.helm([...args, '--destination', dest], `panel-helm-pull-${variant}`))
    .exitCode;
}

/** The bytes `helm pull` leaves in a fresh directory (the last pull of that chart version). */
async function pulledBytes(
  session: Session,
  variant: 'oci' | 'classic',
  name: string,
  version: string,
): Promise<Buffer> {
  expect(await pullExitCode(session, variant, name, version)).toBe(0);
  return fs.readFile(
    path.join(session.work, `pull-${variant}-${name}-${version}`, chartFileName(name, version)),
  );
}

async function indexOf(session: Session) {
  const res = await rawGetIndex(session.repoName, adminCredential());
  expect(res.status, 'index.yaml').toBe(200);
  return parseIndex(res.body.toString('utf8'));
}

const chartValues = (session: Session, chartName: string, version?: string) => ({
  repoName: session.repoName,
  chartName,
  ...(version ? { version } : {}),
});

interface Detail {
  name: string;
  version: string;
  description?: string;
  appVersion?: string;
  type?: string;
  digest: string;
  size: number;
  createdAt: string;
  lastUpdatedAt?: string;
}

async function detailOf(session: Session, chartName: string, version: string): Promise<Detail> {
  return expectContract(
    'getHelmChartDetail',
    await callOperation('getHelmChartDetail', chartValues(session, chartName, version)),
  ) as Detail;
}

async function versionsOf(session: Session, chartName: string): Promise<string[]> {
  const rows = expectContract(
    'getHelmChartVersions',
    await callOperation('getHelmChartVersions', chartValues(session, chartName)),
  ) as { version: string }[];
  return rows.map((row) => row.version).sort();
}

async function chartNames(session: Session): Promise<string[]> {
  const page = expectContract(
    'searchHelmCharts',
    await callOperation('searchHelmCharts', { repoName: session.repoName }, { query: 'size=100' }),
  ) as { content: { name: string }[] };
  return page.content.map((row) => row.name).sort();
}

test.describe('the Helm panel API against what helm pushed', () => {
  test.setTimeout(300_000);

  test('names every operation of the Helm panel API', () => {
    expectCovers(EXERCISED, '/api/helm/');
  });

  test('lists, gets and describes OCI and classic charts, and the answers match helm and index.yaml', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'helm-panel-read');
    const web = `e2e-${seeder.runId}-web`;
    const lib = `e2e-${seeder.runId}-lib`;
    // OCI: `web` twice (the second has no `type`), classic: `lib` twice (a library and an application).
    const web1 = await packageChart(session, {
      name: web,
      version: '1.0.0',
      appVersion: '1.16.0',
      type: 'application',
    });
    const web2 = await packageChart(session, { name: web, version: '1.1.0', appVersion: '2.0.0' });
    const lib1 = await packageChart(session, { name: lib, version: '0.1.0', type: 'library' });
    const lib2 = await packageChart(session, { name: lib, version: '0.2.0', type: 'application' });
    const manifestDigests = new Map<string, string>();
    manifestDigests.set('1.0.0', await pushOci(session, web1));
    manifestDigests.set('1.1.0', await pushOci(session, web2));
    await pushClassic(session, lib1);
    await pushClassic(session, lib2);

    // GET /charts/{repo}: one row per chart, the newest version as `latestVersion`.
    const found = expectContract(
      'searchHelmCharts',
      await callOperation('searchHelmCharts', { repoName: session.repoName }),
    ) as {
      content: { name: string; latestVersion: string; description?: string; type?: string }[];
      page: { totalElements: number };
    };
    expect(found.page.totalElements).toBe(2);
    const row = (name: string) => found.content.find((item) => item.name === name);
    expect(row(web)).toMatchObject({
      latestVersion: '1.1.0',
      description: `panel contract ${web}`,
    });
    expect(row(web)?.type, 'the newest version has no type').toBeUndefined();
    expect(row(lib)).toMatchObject({
      latestVersion: '0.2.0',
      description: `panel contract ${lib}`,
      type: 'application',
    });

    // GET .../{chart}: every version with the fields `helm show chart` prints, and the digest and size of the
    // stored chart file.
    const webVersions = expectContract(
      'getHelmChartVersions',
      await callOperation('getHelmChartVersions', chartValues(session, web)),
    ) as (Detail & { createdAt: string })[];
    expect(webVersions.map((item) => item.version).sort()).toEqual(['1.0.0', '1.1.0']);
    for (const chart of [web1, web2]) {
      const item = webVersions.find((entry) => entry.version === chart.version);
      expect(item, chart.version).toMatchObject({
        appVersion: chart.appVersion,
        description: `panel contract ${web}`,
        // `helm push` sends the .tgz as the chart layer verbatim.
        digest: sha256(chart.tgz),
        size: chart.tgz.length,
      });
      expect(item?.type, `type of ${chart.version}`).toBe(chart.type);
    }
    const libVersions = expectContract(
      'getHelmChartVersions',
      await callOperation('getHelmChartVersions', chartValues(session, lib)),
    ) as Detail[];
    expect(libVersions.map((item) => item.version).sort()).toEqual(['0.1.0', '0.2.0']);
    expect(libVersions.find((item) => item.version === '0.1.0')?.type).toBe('library');
    expect(libVersions.find((item) => item.version === '0.1.0')?.appVersion).toBeUndefined();

    // GET .../{chart}/{version}: the detail equals `helm show chart` of the same version.
    for (const [variant, chart] of [
      ['oci', web1],
      ['oci', web2],
      ['classic', lib1],
      ['classic', lib2],
    ] as ['oci' | 'classic', ChartFacts][]) {
      const shown = await showChart(session, variant, chart.name, chart.version);
      const detail = await detailOf(session, chart.name, chart.version);
      expect(detail, `${chart.name}@${chart.version}`).toMatchObject({
        name: shown.name,
        version: shown.version,
        description: shown.description,
      });
      expect(detail.appVersion, `${chart.version} appVersion`).toBe(shown.appVersion);
      expect(detail.type, `${chart.version} type`).toBe(shown.type);
      expect(Date.parse(detail.createdAt)).toBeLessThanOrEqual(
        Date.parse(detail.lastUpdatedAt ?? detail.createdAt),
      );
      // The digest and size are those of the file a client downloads.
      const download = await rawDownloadChart(
        session.repoName,
        adminCredential(),
        chart.name,
        chart.version,
      );
      expect(download.status).toBe(200);
      expect({ digest: detail.digest, size: detail.size }, `${chart.version} stored file`).toEqual({
        digest: sha256(download.body),
        size: download.body.length,
      });
      // ... and `helm pull` of it (OCI or classic) leaves the same bytes.
      expect(sha256(await pulledBytes(session, variant, chart.name, chart.version))).toBe(
        detail.digest,
      );
    }
    // A chart pushed over OCI keeps the bytes helm sent, so the digest is the local .tgz's.
    expect((await detailOf(session, web, '1.0.0')).digest).toBe(sha256(web1.tgz));

    // The classic view (index.yaml) lists BOTH kinds with the same digest, appVersion, type and time.
    const index = await indexOf(session);
    for (const chart of [web1, web2, lib1, lib2]) {
      const entry = indexEntry(index, chart.name, chart.version) as IndexEntry;
      const detail = await detailOf(session, chart.name, chart.version);
      expect(entry, `index.yaml has ${chart.name}@${chart.version}`).toBeDefined();
      expect(entry).toMatchObject({
        digest: detail.digest,
        appVersion: chart.appVersion,
        type: chart.type,
        urls: [`charts/${chartFileName(chart.name, chart.version)}`],
      });
      expect(Date.parse(entry.created ?? '')).toBe(Date.parse(detail.createdAt));
    }

    // GET .../{chart}/tags: the OCI tags and manifest digests of an OCI chart, nothing for a classic chart.
    const webTags = expectContract(
      'getHelmChartOciTags',
      await callOperation('getHelmChartOciTags', chartValues(session, web)),
    ) as string[];
    expect(webTags).toEqual(
      expect.arrayContaining(['1.0.0', '1.1.0', ...manifestDigests.values()]),
    );
    expect(
      expectContract(
        'getHelmChartOciTags',
        await callOperation('getHelmChartOciTags', chartValues(session, lib)),
      ),
    ).toEqual([]);
  });

  test('answers the failures the spec declares, with the schema of an error', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'helm-panel-fail');
    const chart = `e2e-${seeder.runId}-fail`;
    const built = await packageChart(session, { name: chart, version: '1.0.0' });
    await pushClassic(session, built);
    const missing = chartValues(session, 'no-such-chart');
    const missingVersion = chartValues(session, chart, '9.9.9');

    expectFailure(
      'searchHelmCharts',
      await callOperation('searchHelmCharts', { repoName: 'e2e-no-such-repo' }),
      404,
      'repoNotFound',
    );
    expectFailure(
      'searchHelmCharts',
      await callOperation('searchHelmCharts', { repoName: session.repoName }, { anonymous: true }),
      401,
      'loginRequired',
    );
    expectFailure(
      'getHelmChartVersions',
      await callOperation('getHelmChartVersions', missing),
      404,
      'chartNotFound',
    );
    expectFailure(
      'getHelmChartOciTags',
      await callOperation('getHelmChartOciTags', missing),
      404,
      'chartNotFound',
    );
    expectFailure(
      'getHelmChartDetail',
      await callOperation('getHelmChartDetail', missingVersion),
      404,
      'chartNotFound',
    );
    expectFailure(
      'deleteHelmChartVersion',
      await callOperation('deleteHelmChartVersion', missingVersion),
      404,
      'chartNotFound',
    );
    expectFailure(
      'deleteAllHelmChartVersions',
      await callOperation('deleteAllHelmChartVersions', missing),
      404,
      'chartNotFound',
    );

    // A delete of a version that does not exist deleted nothing: the chart has exactly one version and
    // still serves it (the shape of RPS-1573 for Maven: a delete of a missing item must not cascade).
    expect(await versionsOf(session, chart)).toEqual(['1.0.0']);
    expect(indexEntry(await indexOf(session), chart, '1.0.0')).toBeDefined();
    expect(await pullExitCode(session, 'classic', chart, '1.0.0')).toBe(0);
  });

  test('pages, sorts and narrows the chart list, and refuses what the spec bounds', async ({
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const seeded = { name: repo.name, type: RepoType.HELM };
    for (const index of [1, 2, 3, 4, 5]) {
      await seedPackage(seeded, seeder, { index });
    }
    await expectPagingSweep<{ name: string }>({
      operationId: 'searchHelmCharts',
      values: { repoName: repo.name },
      total: 5,
      keyOf: (item) => item.name,
      sorts: [{ property: 'name', value: (item) => item.name }],
    });
    const narrowed = expectContract(
      'searchHelmCharts',
      await callOperation('searchHelmCharts', { repoName: repo.name }, { query: 'q=pkg-3' }),
    ) as { content: { name: string }[] };
    expect(narrowed.content.map((item) => item.name)).toEqual([`e2e-${seeder.runId}-pkg-3`]);
  });

  test('deleting a version of an OCI chart removes it from index.yaml, the download and helm; the sibling still pulls its bytes', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'helm-panel-del-oci');
    const web = `e2e-${seeder.runId}-web`;
    const web1 = await packageChart(session, { name: web, version: '1.0.0', appVersion: '1.16.0' });
    const web2 = await packageChart(session, { name: web, version: '1.1.0', appVersion: '2.0.0' });
    const digest1 = await pushOci(session, web1);
    await pushOci(session, web2);
    expect(await versionsOf(session, web)).toEqual(['1.0.0', '1.1.0']);
    expect(indexEntry(await indexOf(session), web, '1.0.0')).toBeDefined();

    expectContract(
      'deleteHelmChartVersion',
      await callOperation('deleteHelmChartVersion', chartValues(session, web, '1.0.0')),
    );

    // The panel.
    expect(await versionsOf(session, web)).toEqual(['1.1.0']);
    expectFailure(
      'getHelmChartDetail',
      await callOperation('getHelmChartDetail', chartValues(session, web, '1.0.0')),
      404,
      'chartNotFound',
    );
    const tags = expectContract(
      'getHelmChartOciTags',
      await callOperation('getHelmChartOciTags', chartValues(session, web)),
    ) as string[];
    expect(tags).toContain('1.1.0');
    expect(tags).not.toContain('1.0.0');
    expect(tags).not.toContain(digest1);
    // The wire: index.yaml, the classic download, the OCI manifest by tag and by digest, and the real client.
    const index = await indexOf(session);
    expect(indexEntry(index, web, '1.0.0'), 'index.yaml drops the version').toBeUndefined();
    expect(indexEntry(index, web, '1.1.0'), 'and keeps the sibling').toBeDefined();
    expect((await rawDownloadChart(session.repoName, adminCredential(), web, '1.0.0')).status).toBe(
      404,
    );
    expect((await rawGetManifest(session.repoName, adminCredential(), web, '1.0.0')).status).toBe(
      404,
    );
    expect((await rawGetManifest(session.repoName, adminCredential(), web, digest1)).status).toBe(
      404,
    );
    expect(
      await pullExitCode(session, 'oci', web, '1.0.0'),
      'helm pull of the deleted version',
    ).not.toBe(0);
    expect(
      (
        await session.helm(
          [
            'show',
            'chart',
            ociChartRef(session.repoName, web),
            '--version',
            '1.0.0',
            ...plainHttpFlag(),
          ],
          'panel-helm-show-gone',
        )
      ).exitCode,
    ).not.toBe(0);
    expect(sha256(await pulledBytes(session, 'oci', web, '1.1.0')), 'the sibling').toBe(
      sha256(web2.tgz),
    );
    expect(sha256(await pulledBytes(session, 'classic', web, '1.1.0'))).toBe(sha256(web2.tgz));

    // Deleting the last version removes the chart itself.
    expectContract(
      'deleteHelmChartVersion',
      await callOperation('deleteHelmChartVersion', chartValues(session, web, '1.1.0')),
    );
    expect(await chartNames(session)).toEqual([]);
    expectFailure(
      'getHelmChartVersions',
      await callOperation('getHelmChartVersions', chartValues(session, web)),
      404,
      'chartNotFound',
    );
    expect((await indexOf(session)).entries[web] ?? [], 'index.yaml').toEqual([]);
    expect(await pullExitCode(session, 'oci', web, '1.1.0')).not.toBe(0);
  });

  test('deleting a version, then a whole classic chart, removes it from index.yaml and helm; other charts are untouched', async ({
    seeder,
  }) => {
    const session = await newSession(seeder, 'helm-panel-del-classic');
    const lib = `e2e-${seeder.runId}-lib`;
    const keep = `e2e-${seeder.runId}-keep`;
    const lib1 = await packageChart(session, { name: lib, version: '0.1.0', type: 'library' });
    const lib2 = await packageChart(session, { name: lib, version: '0.2.0', type: 'library' });
    const kept = await packageChart(session, { name: keep, version: '3.0.0', appVersion: '3' });
    await pushClassic(session, lib1);
    await pushClassic(session, lib2);
    await pushClassic(session, kept);
    const keptBefore = await detailOf(session, keep, '3.0.0');
    const lib2Stored = (await detailOf(session, lib, '0.2.0')).digest;

    expectContract(
      'deleteHelmChartVersion',
      await callOperation('deleteHelmChartVersion', chartValues(session, lib, '0.1.0')),
    );
    expect(await versionsOf(session, lib)).toEqual(['0.2.0']);
    const afterVersion = await indexOf(session);
    expect(indexEntry(afterVersion, lib, '0.1.0'), 'index.yaml drops the version').toBeUndefined();
    expect(indexEntry(afterVersion, lib, '0.2.0')).toBeDefined();
    expect((await rawDownloadChart(session.repoName, adminCredential(), lib, '0.1.0')).status).toBe(
      404,
    );
    expect(
      await pullExitCode(session, 'classic', lib, '0.1.0'),
      'helm pull of the deleted version',
    ).not.toBe(0);
    expect(sha256(await pulledBytes(session, 'classic', lib, '0.2.0')), 'the sibling').toBe(
      lib2Stored,
    );
    expectFailure(
      'deleteHelmChartVersion',
      await callOperation('deleteHelmChartVersion', chartValues(session, lib, '0.1.0')),
      404,
      'chartNotFound',
    );

    // The whole chart.
    expectContract(
      'deleteAllHelmChartVersions',
      await callOperation('deleteAllHelmChartVersions', chartValues(session, lib)),
    );
    expect(await chartNames(session)).toEqual([keep]);
    expectFailure(
      'getHelmChartVersions',
      await callOperation('getHelmChartVersions', chartValues(session, lib)),
      404,
      'chartNotFound',
    );
    expectFailure(
      'deleteAllHelmChartVersions',
      await callOperation('deleteAllHelmChartVersions', chartValues(session, lib)),
      404,
      'chartNotFound',
    );
    const afterAll = await indexOf(session);
    expect(afterAll.entries[lib] ?? [], 'index.yaml has no version of the chart').toEqual([]);
    expect((await rawDownloadChart(session.repoName, adminCredential(), lib, '0.2.0')).status).toBe(
      404,
    );
    expect(await pullExitCode(session, 'classic', lib, '0.2.0')).not.toBe(0);

    // The other chart is exactly as it was.
    expect(await detailOf(session, keep, '3.0.0')).toEqual(keptBefore);
    expect(indexEntry(afterAll, keep, '3.0.0')).toBeDefined();
    expect(sha256(await pulledBytes(session, 'classic', keep, '3.0.0'))).toBe(keptBefore.digest);
  });
});
