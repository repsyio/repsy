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
 * Both Helm protocols' server rules, pinned at the protocol level with raw HTTP (no `helm`
 * client), the helm analogue of `tests/docker/registry-rules.spec.ts`. Every status/detail here was
 * read from `AbstractHelmOci*ProtocolMethodHandler`/`AbstractHelmChart*ProtocolMethodHandler`/
 * `HelmHeaderPreProcessor`/`HelmAuthPreProcessor` first and then confirmed live against a running
 * instance. Sections R1-R14 mirror the plan's own hypothesis/rule numbering.
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  buildChart,
  buildChartFromRawYaml,
  buildChartWithoutChartYaml,
} from '../../src/clients/helm-chart.js';
import {
  adminCredential,
  chartFileName,
  indexEntry,
  ociErrorOf,
  parseIndex,
  rawDeleteChart,
  rawDownloadChart,
  rawGetIndex,
  rawGetManifest,
  rawGetTagsList,
  rawHeadBlob,
  rawPing,
  rawPutManifest,
  rawStartUpload,
  rawUploadBlob,
  rawUploadChart,
  rawUploadChartMissingPart,
  sha256Hex,
  type RawResponse,
} from '../../src/clients/helm-raw.js';
import { repoPath } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  chart: string;
}

async function newRepo(
  seeder: Seeder,
  label: string,
  opts: { privateRepo?: boolean } = {},
): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: opts.privateRepo ?? true });
  return { repoName: repo.name, chart: `e2e-${seeder.runId}-${label}` };
}

function expectOci(res: RawResponse, status: number, code: string | undefined): void {
  const oci = ociErrorOf(res.body);
  expect(res.status, `answered ${res.status} ${oci?.message ?? ''}`).toBe(status);
  if (code !== undefined) {
    expect(oci?.code, 'the OCI error code').toBe(code);
  }
}

test.describe('helm registry rules (raw HTTP)', () => {
  test("R1: the /v2/ ping is Docker's own (both Helm protocols are steered to its token endpoint)", async () => {
    const ping = await rawPing();
    expect(ping.status, 'GET /v2/ without auth is a 401 challenge, from the Docker provider').toBe(
      401,
    );
    expect(ping.wwwAuthenticate).toContain('Bearer');
  });

  test(
    'R2: OCI auth matrix -- single-hop Basic (no token exchange, unlike Docker)',
    { tag: ['@auth', '@negative'] },
    async ({ seeder }) => {
      const priv = await newRepo(seeder, 'ociauthpriv');
      const pub = await newRepo(seeder, 'ociauthpub', { privateRepo: false });
      const admin = adminCredential();

      const noHeaderRead = await rawGetManifest(priv.repoName, {}, priv.chart, 'doesnotexist');
      expectOci(noHeaderRead, 401, 'UNAUTHORIZED');
      expect(noHeaderRead.wwwAuthenticate).toContain('Basic realm="Repsy"');

      const noHeaderWrite = await rawStartUpload(priv.repoName, {}, priv.chart);
      expectOci(noHeaderWrite, 401, 'UNAUTHORIZED');

      const wrongCred = {
        transport: 'basic' as const,
        username: 'admin',
        password: 'definitely-wrong',
        kind: 'password' as const,
      };
      const wrongRead = await rawGetManifest(priv.repoName, wrongCred, priv.chart, 'doesnotexist');
      expectOci(wrongRead, 401, 'UNAUTHORIZED');

      const ro = await seeder.createToken(priv.repoName, { readOnly: true });
      const roCred = {
        transport: 'basic' as const,
        username: ro.username,
        password: ro.token,
        kind: 'token' as const,
      };
      const roWrite = await rawStartUpload(priv.repoName, roCred, priv.chart);
      expectOci(roWrite, 401, 'UNAUTHORIZED');
      const roRead = await rawGetManifest(priv.repoName, roCred, priv.chart, 'doesnotexist');
      expect(roRead.status, 'a READ with the same ro token still succeeds (404: no such tag)').toBe(
        404,
      );

      const pubRead = await rawGetManifest(pub.repoName, {}, pub.chart, 'doesnotexist');
      expect(pubRead.status, 'anonymous READ on a public repo (404: no such tag)').toBe(404);
      const pubWrite = await rawStartUpload(pub.repoName, {}, pub.chart);
      expectOci(pubWrite, 401, 'UNAUTHORIZED');

      const adminRead = await rawGetManifest(priv.repoName, admin, priv.chart, 'doesnotexist');
      expect(adminRead.status).toBe(404);
    },
  );

  test(
    'R3: blob upload flows (monolithic, chunked, wrong digest, dedup)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'blobflows');
      const admin = adminCredential();
      const built = await buildChart({ name: layout.chart, version: '0.1.0', marker: 'r3' });

      const monolithic = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.chart,
        built.tgzBytes,
        built.tgzDigest,
        { mode: 'monolithic' },
      );
      expect(monolithic.status, 'monolithic upload').toBe(201);
      expect(monolithic.digestHeader).toBe(built.tgzDigest);

      const chunked = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.chart,
        built.tgzBytes,
        built.tgzDigest,
        { mode: 'patch' },
      );
      expect(chunked.status, 'chunked (PATCH) upload of the same digest -- dedup').toBe(201);

      const wrongDigest = `sha256:${'0'.repeat(64)}`;
      const wrong = await rawUploadBlob(
        layout.repoName,
        admin,
        layout.chart,
        built.tgzBytes,
        wrongDigest,
        {
          mode: 'monolithic',
        },
      );
      expectOci(wrong, 400, 'DIGEST_INVALID');

      const head = await rawHeadBlob(layout.repoName, admin, layout.chart, built.tgzDigest);
      expect(head.status).toBe(200);
    },
  );

  test(
    'R4: manifest push validation (missing blob, malformed JSON, empty layers, no Content-Type)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'manifestvalidation');
      const admin = adminCredential();
      const built = await buildChart({ name: layout.chart, version: '0.1.0', marker: 'r4' });

      const missingBlob = await rawPutManifest(
        layout.repoName,
        admin,
        layout.chart,
        'tag1',
        Buffer.from(
          JSON.stringify({
            schemaVersion: 2,
            mediaType: 'application/vnd.oci.image.manifest.v1+json',
            config: { mediaType: 'x', digest: `sha256:${'1'.repeat(64)}`, size: 1 },
            layers: [{ mediaType: 'x', digest: built.tgzDigest, size: built.tgzBytes.length }],
          }),
        ),
        'application/vnd.oci.image.manifest.v1+json',
      );
      expectOci(missingBlob, 404, 'MANIFEST_BLOB_UNKNOWN');

      const malformed = await rawPutManifest(
        layout.repoName,
        admin,
        layout.chart,
        'tag1',
        Buffer.from('not json'),
        'application/vnd.oci.image.manifest.v1+json',
      );
      expectOci(malformed, 400, 'MANIFEST_INVALID');

      const emptyLayers = await rawPutManifest(
        layout.repoName,
        admin,
        layout.chart,
        'tag1',
        Buffer.from(
          JSON.stringify({
            schemaVersion: 2,
            mediaType: 'application/vnd.oci.image.manifest.v1+json',
            config: { mediaType: 'x', digest: `sha256:${'1'.repeat(64)}`, size: 1 },
            layers: [],
          }),
        ),
        'application/vnd.oci.image.manifest.v1+json',
      );
      expectOci(emptyLayers, 400, 'MANIFEST_INVALID');

      const noContentType = await rawPutManifest(
        layout.repoName,
        admin,
        layout.chart,
        'tag1',
        Buffer.from('{}'),
      );
      // Candidate B-H7 (RPS-1110, fixed): a missing Content-Type header now throws a
      // BadRequestException, rendered as the OCI errors[] envelope like every other push failure,
      // instead of a bare bodyless 400.
      expectOci(noContentType, 400, 'MANIFEST_INVALID');
      expect(ociErrorOf(noContentType.body)?.detail).toBe('manifestContentTypeMissing');
    },
  );

  test('R5: the OCI override rule', { tag: ['@settings', '@negative'] }, async ({ seeder }) => {
    const layout = await newRepo(seeder, 'ocioverride');
    const admin = adminCredential();

    const first = await buildChart({ name: layout.chart, version: '0.1.0', marker: 'r5-v1' });
    await rawUploadBlob(layout.repoName, admin, layout.chart, first.tgzBytes, first.tgzDigest);
    const manifest1 = Buffer.from(
      JSON.stringify({
        schemaVersion: 2,
        mediaType: 'application/vnd.oci.image.manifest.v1+json',
        config: { mediaType: 'x', digest: `sha256:${'1'.repeat(64)}`, size: 1 },
        layers: [{ mediaType: 'x', digest: first.tgzDigest, size: first.tgzBytes.length }],
      }),
    );
    await rawUploadBlob(
      layout.repoName,
      admin,
      layout.chart,
      Buffer.from('{}'),
      `sha256:${'1'.repeat(64)}`,
    );
    const push1 = await rawPutManifest(
      layout.repoName,
      admin,
      layout.chart,
      'tag1',
      manifest1,
      'application/vnd.oci.image.manifest.v1+json',
    );
    expect(push1.status, 'first push of a fresh tag').toBe(201);

    await seeder.setSettings(layout.repoName, {
      privateRepo: true,
      allowOverride: false,
    });

    const second = await buildChart({ name: layout.chart, version: '0.1.0', marker: 'r5-v2' });
    await rawUploadBlob(layout.repoName, admin, layout.chart, second.tgzBytes, second.tgzDigest);
    const manifest2 = Buffer.from(
      JSON.stringify({
        schemaVersion: 2,
        mediaType: 'application/vnd.oci.image.manifest.v1+json',
        config: { mediaType: 'x', digest: `sha256:${'1'.repeat(64)}`, size: 1 },
        layers: [{ mediaType: 'x', digest: second.tgzDigest, size: second.tgzBytes.length }],
      }),
    );
    const refused = await rawPutManifest(
      layout.repoName,
      admin,
      layout.chart,
      'tag1',
      manifest2,
      'application/vnd.oci.image.manifest.v1+json',
    );
    expectOci(refused, 409, 'DENIED');
    expect(ociErrorOf(refused.body)?.detail).toBe('chartAlreadyExists');

    const getAfterRefused = await rawGetManifest(layout.repoName, admin, layout.chart, 'tag1');
    expect(sha256Hex(getAfterRefused.body)).toBe(sha256Hex(manifest1));
  });

  test('R8/B-H3: GET tags/list is served', { tag: ['@negative'] }, async ({ seeder }) => {
    const layout = await newRepo(seeder, 'tagslist');
    const admin = adminCredential();
    const res = await rawGetTagsList(layout.repoName, admin, layout.chart);
    expect(res.status, 'tags/list should be served').toBe(200);
    expect(
      (JSON.parse(res.body.toString('utf8')) as { name?: string }).name,
      'the repository-qualified name of the distribution spec (RPS-1557)',
    ).toBe(`${repoPath(layout.repoName)}/${layout.chart}`);
  });

  test(
    'R11: classic upload routes (missing chart part, both routes accept the same request)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'classicupload');
      const admin = adminCredential();

      const missingPart = await rawUploadChartMissingPart(layout.repoName, admin);
      expect(missingPart.status, "a missing 'chart' part -- plain text, no JSON envelope").toBe(
        400,
      );
      expect(missingPart.body.toString('utf8')).toBe("Missing 'chart' part");
      expect(missingPart.msgId, 'no msgId (not the panel envelope)').toBeUndefined();

      const built = await buildChart({ name: layout.chart, version: '0.1.0', marker: 'r11' });
      const pluginRoute = await rawUploadChart(
        layout.repoName,
        admin,
        built.tgzBytes,
        chartFileName(layout.chart, '0.1.0'),
        { route: 'plugin' },
      );
      expect(pluginRoute.status, 'POST /api/<repo>/charts (the cm-push route)').toBe(201);

      const built2 = await buildChart({
        name: `${layout.chart}-b`,
        version: '0.1.0',
        marker: 'r11b',
      });
      const chartMuseumRoute = await rawUploadChart(
        layout.repoName,
        admin,
        built2.tgzBytes,
        chartFileName(`${layout.chart}-b`, '0.1.0'),
        { route: 'chartmuseum' },
      );
      expect(
        chartMuseumRoute.status,
        "POST /<repo>/api/charts (ChartMuseum's own historical route) accepts the same request",
      ).toBe(201);
    },
  );

  test(
    'R4b: OCI manifest refusals (chartNameMismatch, manifestLayerInvalid, manifestNameTooLong, ' +
      'manifestReferenceTooLong)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'ocimanifestrefusals');
      const admin = adminCredential();

      // chartNameMismatch: path name differs from Chart.yaml name field
      const built = await buildChart({
        name: 'correct-name',
        version: '1.0.0',
        marker: 'r4b-namemismatch',
      });
      const mismatchManifest = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'1'.repeat(64)}`, size: 1 },
          layers: [{ mediaType: 'x', digest: built.tgzDigest, size: built.tgzBytes.length }],
        }),
      );
      await rawUploadBlob(layout.repoName, admin, 'correct-name', built.tgzBytes, built.tgzDigest);
      await rawUploadBlob(
        layout.repoName,
        admin,
        'correct-name',
        Buffer.from('{}'),
        `sha256:${'1'.repeat(64)}`,
      );
      const mismatchPush = await rawPutManifest(
        layout.repoName,
        admin,
        'wrong-name',
        '1.0.0',
        mismatchManifest,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expectOci(mismatchPush, 400, 'chartNameMismatch');

      // manifestLayerInvalid: missing or invalid digest/size in layer
      const invalidLayerManifest = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'2'.repeat(64)}`, size: 1 },
          layers: [{ mediaType: 'x', size: built.tgzBytes.length }],
        }),
      );
      const invalidLayerPush = await rawPutManifest(
        layout.repoName,
        admin,
        'correct-name',
        '2.0.0',
        invalidLayerManifest,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expectOci(invalidLayerPush, 400, 'manifestLayerInvalid');

      // manifestNameTooLong: name exceeds MAX_OCI_MANIFEST_NAME_LENGTH (255)
      const longName = 'a'.repeat(256);
      const longNameManifest = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'3'.repeat(64)}`, size: 1 },
          layers: [{ mediaType: 'x', digest: built.tgzDigest, size: built.tgzBytes.length }],
        }),
      );
      const longNamePush = await rawPutManifest(
        layout.repoName,
        admin,
        longName,
        '3.0.0',
        longNameManifest,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expectOci(longNamePush, 400, 'manifestNameTooLong');

      // manifestReferenceTooLong: reference exceeds MAX_OCI_MANIFEST_REFERENCE_LENGTH (255)
      const longReference = 'a'.repeat(256);
      const longRefManifest = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'4'.repeat(64)}`, size: 1 },
          layers: [{ mediaType: 'x', digest: built.tgzDigest, size: built.tgzBytes.length }],
        }),
      );
      const longRefPush = await rawPutManifest(
        layout.repoName,
        admin,
        'correct-name',
        longReference,
        longRefManifest,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expectOci(longRefPush, 400, 'manifestReferenceTooLong');
    },
  );

  test(
    'R11b: classic Chart.yaml validation (12 refusal branches from HelmChartParser)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'chartyamlvalidation');
      const admin = adminCredential();

      interface TestCase {
        name: string;
        chartYaml: string;
        expectedMsgId: string;
      }

      const cases: TestCase[] = [
        {
          name: 'chartYamlNotFound',
          chartYaml: '', // Placeholder; buildChartWithoutChartYaml handles this case
          expectedMsgId: 'chartYamlNotFound',
        },
        {
          name: 'chartYamlInvalid',
          chartYaml: '{ invalid yaml : [}',
          expectedMsgId: 'chartYamlInvalid',
        },
        {
          name: 'chartNameMissing',
          chartYaml: 'apiVersion: v2\nversion: 1.0.0\n',
          expectedMsgId: 'chartNameMissing',
        },
        {
          name: 'chartNameInvalid',
          chartYaml: 'apiVersion: v2\nname: UPPERCASE\nversion: 1.0.0\n',
          expectedMsgId: 'chartNameInvalid',
        },
        {
          name: 'chartNameTooLong',
          chartYaml: `apiVersion: v2\nname: ${'a'.repeat(256)}\nversion: 1.0.0\n`,
          expectedMsgId: 'chartNameTooLong',
        },
        {
          name: 'chartVersionMissing',
          chartYaml: 'apiVersion: v2\nname: test-chart\n',
          expectedMsgId: 'chartVersionMissing',
        },
        {
          name: 'chartVersionInvalid',
          chartYaml: 'apiVersion: v2\nname: test-chart\nversion: not-a-version\n',
          expectedMsgId: 'chartVersionInvalid',
        },
        {
          name: 'chartVersionTooLong',
          chartYaml: `apiVersion: v2\nname: test-chart\nversion: ${'1'.repeat(256)}\n`,
          expectedMsgId: 'chartVersionTooLong',
        },
        {
          name: 'chartApiVersionInvalid',
          chartYaml: `apiVersion: ${'x'.repeat(256)}\nname: test-chart\nversion: 1.0.0\n`,
          expectedMsgId: 'chartApiVersionInvalid',
        },
        {
          name: 'chartDependenciesInvalid (non-list)',
          chartYaml:
            'apiVersion: v2\nname: test-chart\nversion: 1.0.0\ndependencies: "not-a-list"\n',
          expectedMsgId: 'chartDependenciesInvalid',
        },
        {
          name: 'chartAppVersionTooLong',
          chartYaml: `apiVersion: v2\nname: test-chart\nversion: 1.0.0\nappVersion: ${'x'.repeat(256)}\n`,
          expectedMsgId: 'chartAppVersionTooLong',
        },
        {
          name: 'chartTypeInvalid',
          chartYaml: `apiVersion: v2\nname: test-chart\nversion: 1.0.0\ntype: ${'x'.repeat(256)}\n`,
          expectedMsgId: 'chartTypeInvalid',
        },
      ];

      for (const testCase of cases) {
        const built =
          testCase.name === 'chartYamlNotFound'
            ? await buildChartWithoutChartYaml({
                name: 'test-chart',
                marker: `r11b-${testCase.name}`,
              })
            : await buildChartFromRawYaml({
                chartYaml: testCase.chartYaml,
                marker: `r11b-${testCase.name}`,
                name: 'test-chart',
                version: '1.0.0',
              });

        const push = await rawUploadChart(
          layout.repoName,
          admin,
          built.tgzBytes,
          chartFileName('test-chart', '1.0.0'),
        );

        expect(push.status, `${testCase.name} should be 400`).toBe(400);
        expect(push.msgId, `${testCase.name} should have msgId ${testCase.expectedMsgId}`).toBe(
          testCase.expectedMsgId,
        );

        // Verify nothing was stored
        const downloaded = await rawDownloadChart(layout.repoName, admin, 'test-chart', '1.0.0');
        expect(downloaded.status, `${testCase.name} should not store anything`).toBe(404);
      }
    },
  );

  test(
    'R15: Deny holds across protocols (OCI then classic, classic then OCI, identical re-push)',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'crossprotocoldeny');
      const admin = adminCredential();

      // Setup: OCI push first
      const built = await buildChart({
        name: 'mychart',
        version: '1.0.0',
        marker: 'r15-oci-first',
      });
      await rawUploadBlob(layout.repoName, admin, 'mychart', built.tgzBytes, built.tgzDigest);
      await rawUploadBlob(
        layout.repoName,
        admin,
        'mychart',
        Buffer.from('{}'),
        `sha256:${'1'.repeat(64)}`,
      );
      const ociManifest = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'1'.repeat(64)}`, size: 1 },
          layers: [{ mediaType: 'x', digest: built.tgzDigest, size: built.tgzBytes.length }],
        }),
      );
      const ociPush = await rawPutManifest(
        layout.repoName,
        admin,
        'mychart',
        '1.0.0',
        ociManifest,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expect(ociPush.status, 'OCI push succeeds').toBe(201);

      // Try classic push of same name:version with DIFFERENT bytes (should be refused 409)
      const builtDifferent = await buildChart({
        name: 'mychart',
        version: '1.0.0',
        marker: 'r15-classic-different',
      });
      const classicPush = await rawUploadChart(
        layout.repoName,
        admin,
        builtDifferent.tgzBytes,
        chartFileName('mychart', '1.0.0'),
      );
      expect(
        classicPush.status,
        'classic push of same coordinate with different bytes is refused',
      ).toBe(409);
      expect(classicPush.msgId).toBe('chartAlreadyExists');

      // Verify original OCI chart is still accessible
      const ociPull = await rawGetManifest(layout.repoName, admin, 'mychart', '1.0.0');
      expect(ociPull.status, 'OCI chart still accessible').toBe(200);

      // Now test reverse: classic push first
      const layout2 = await newRepo(seeder, 'crossprotocoldeny2');
      const classicBuilt = await buildChart({
        name: 'anotherchart',
        version: '1.0.0',
        marker: 'r15-classic-first',
      });
      const classicFirst = await rawUploadChart(
        layout2.repoName,
        admin,
        classicBuilt.tgzBytes,
        chartFileName('anotherchart', '1.0.0'),
      );
      expect(classicFirst.status, 'classic push succeeds').toBe(201);

      // Try OCI push of same name:version with DIFFERENT bytes (should be refused 409)
      const ociBuiltDifferent = await buildChart({
        name: 'anotherchart',
        version: '1.0.0',
        marker: 'r15-oci-different',
      });
      await rawUploadBlob(
        layout2.repoName,
        admin,
        'anotherchart',
        ociBuiltDifferent.tgzBytes,
        ociBuiltDifferent.tgzDigest,
      );
      await rawUploadBlob(
        layout2.repoName,
        admin,
        'anotherchart',
        Buffer.from('{}'),
        `sha256:${'2'.repeat(64)}`,
      );
      const ociManifestDifferent = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'2'.repeat(64)}`, size: 1 },
          layers: [
            {
              mediaType: 'x',
              digest: ociBuiltDifferent.tgzDigest,
              size: ociBuiltDifferent.tgzBytes.length,
            },
          ],
        }),
      );
      const ociSecondPush = await rawPutManifest(
        layout2.repoName,
        admin,
        'anotherchart',
        '1.0.0',
        ociManifestDifferent,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expect(
        ociSecondPush.status,
        'OCI push of same coordinate with different bytes is refused',
      ).toBe(409);
      expect(ociSecondPush.msgId).toBe('chartAlreadyExists');

      // Verify original classic chart is still accessible
      const classicPull = await rawDownloadChart(layout2.repoName, admin, 'anotherchart', '1.0.0');
      expect(classicPull.status, 'classic chart still accessible').toBe(200);

      // Test idempotent re-push: identical bytes over OCI after a classic push should be accepted
      const layout3 = await newRepo(seeder, 'crossprotocolidempotent');
      const shared = await buildChart({
        name: 'samechart',
        version: '1.0.0',
        marker: 'r15-shared',
      });
      const classicInitial = await rawUploadChart(
        layout3.repoName,
        admin,
        shared.tgzBytes,
        chartFileName('samechart', '1.0.0'),
      );
      expect(classicInitial.status).toBe(201);

      // Push identical bytes via OCI (should be accepted as idempotent re-push)
      await rawUploadBlob(layout3.repoName, admin, 'samechart', shared.tgzBytes, shared.tgzDigest);
      await rawUploadBlob(
        layout3.repoName,
        admin,
        'samechart',
        Buffer.from('{}'),
        `sha256:${'3'.repeat(64)}`,
      );
      const ociIdentical = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'3'.repeat(64)}`, size: 1 },
          layers: [{ mediaType: 'x', digest: shared.tgzDigest, size: shared.tgzBytes.length }],
        }),
      );
      const ociIdempotent = await rawPutManifest(
        layout3.repoName,
        admin,
        'samechart',
        '1.0.0',
        ociIdentical,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expect(ociIdempotent.status, 'identical re-push via OCI is accepted').toBe(201);
    },
  );

  test(
    'R12: classic read auth (bare 401 on a private repo, 200 with creds, public anonymous)',
    {
      tag: ['@auth', '@negative'],
    },
    async ({ seeder }) => {
      const priv = await newRepo(seeder, 'classicauthpriv');
      const pub = await newRepo(seeder, 'classicauthpub', { privateRepo: false });
      const admin = adminCredential();

      const noAuth = await rawGetIndex(priv.repoName, {});
      expect(noAuth.status, 'a bare 401 (no JSON envelope) on a classic path').toBe(401);
      expect(noAuth.body, 'no body at all').toHaveLength(0);

      const withAuth = await rawGetIndex(priv.repoName, admin);
      expect(withAuth.status).toBe(200);

      const anonPublic = await rawGetIndex(pub.repoName, {});
      expect(anonPublic.status, 'anonymous read on a public repo').toBe(200);
    },
  );

  test('R13: index.yaml shape (apiVersion, digest prefix -- candidate B-H8, created)', async ({
    seeder,
  }) => {
    const layout = await newRepo(seeder, 'indexshape');
    const admin = adminCredential();
    const built = await buildChart({ name: layout.chart, version: '0.1.0', marker: 'r13' });
    const push = await rawUploadChart(
      layout.repoName,
      admin,
      built.tgzBytes,
      chartFileName(layout.chart, '0.1.0'),
    );
    expect(push.status).toBe(201);

    const indexRes = await rawGetIndex(layout.repoName, admin);
    expect(indexRes.status).toBe(200);
    const index = parseIndex(indexRes.body.toString('utf8'));
    expect(index.apiVersion).toBe('v1');
    const entry = indexEntry(index, layout.chart, '0.1.0');
    expect(entry).toBeDefined();
    expect(
      entry?.digest,
      'candidate (B-H8, observation): digest carries a sha256: prefix, unlike a real `helm repo ' +
        'index` (bare hex) -- low impact, no real client verifies it',
    ).toBe(built.tgzDigest);
    expect(entry?.created, 'an ISO instant').toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(entry?.apiVersion, "the chart's own apiVersion (RPS-1557)").toBe('v2');
    expect(
      entry?.dependencies,
      'a chart that declares none has no dependencies key',
    ).toBeUndefined();
  });

  test(
    'R14: DELETE (admin only, never a deploy token) removes a chart from both the classic ' +
      'index and, for an OCI-pushed chart, the manifest too',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'delete');
      const admin = adminCredential();
      const built = await buildChart({ name: layout.chart, version: '0.1.0', marker: 'r14' });

      const push = await rawUploadChart(
        layout.repoName,
        admin,
        built.tgzBytes,
        chartFileName(layout.chart, '0.1.0'),
      );
      expect(push.status).toBe(201);

      const ro = await seeder.createToken(layout.repoName, { readOnly: true });
      const roCred = {
        transport: 'basic' as const,
        username: ro.username,
        password: ro.token,
        kind: 'token' as const,
      };
      const roDelete = await rawDeleteChart(layout.repoName, roCred, layout.chart, '0.1.0');
      expect(roDelete.status, 'a read-only token cannot delete').toBe(401);

      // RPS-1424: removing a chart's files needs MANAGE (the ADMIN role, as in the panel); a
      // deploy token never has it, read/write or not. A push stays a WRITE.
      const rw = await seeder.createToken(layout.repoName, { readOnly: false });
      const rwCred = {
        transport: 'basic' as const,
        username: rw.username,
        password: rw.token,
        kind: 'token' as const,
      };
      const rwDelete = await rawDeleteChart(layout.repoName, rwCred, layout.chart, '0.1.0');
      expect(rwDelete.status, 'a read-write token cannot delete either').toBe(401);
      expect(
        (await rawDownloadChart(layout.repoName, admin, layout.chart, '0.1.0')).status,
        'the refused delete left the chart',
      ).toBe(200);

      const del = await rawDeleteChart(layout.repoName, admin, layout.chart, '0.1.0');
      expect(del.status).toBe(200);

      const indexRes = await rawGetIndex(layout.repoName, admin);
      const entry = indexEntry(parseIndex(indexRes.body.toString('utf8')), layout.chart, '0.1.0');
      expect(entry, 'gone from the index').toBeUndefined();

      const dlRes = await rawDownloadChart(layout.repoName, admin, layout.chart, '0.1.0');
      expect(dlRes.status, 'gone from download too').toBe(404);

      // An OCI-pushed chart's DELETE also removes its manifest (confirmed live).
      const ociChart = `${layout.chart}-oci`;
      const ociBuilt = await buildChart({ name: ociChart, version: '0.1.0', marker: 'r14-oci' });
      await rawUploadBlob(layout.repoName, admin, ociChart, ociBuilt.tgzBytes, ociBuilt.tgzDigest);
      await rawUploadBlob(
        layout.repoName,
        admin,
        ociChart,
        Buffer.from('{}'),
        `sha256:${'1'.repeat(64)}`,
      );
      const ociManifest = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'1'.repeat(64)}`, size: 1 },
          layers: [{ mediaType: 'x', digest: ociBuilt.tgzDigest, size: ociBuilt.tgzBytes.length }],
        }),
      );
      const ociPush = await rawPutManifest(
        layout.repoName,
        admin,
        ociChart,
        '0.1.0',
        ociManifest,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expect(ociPush.status).toBe(201);

      const ociDel = await rawDeleteChart(layout.repoName, admin, ociChart, '0.1.0');
      expect(ociDel.status).toBe(200);

      const manifestAfterDelete = await rawGetManifest(layout.repoName, admin, ociChart, '0.1.0');
      expect(manifestAfterDelete.status, 'the manifest is gone too').toBe(404);

      // R14 extension: tags/list no longer lists the deleted version
      const tagsAfterDelete = await rawGetTagsList(layout.repoName, admin, ociChart);
      expect(tagsAfterDelete.status, 'tags/list still works').toBe(200);
      const tagsBody = JSON.parse(tagsAfterDelete.body.toString('utf8'));
      expect(tagsBody.tags, 'the deleted version is gone from tags/list').not.toContain('0.1.0');

      // R14 extension: republish of same name:version under Deny (override off) is now accepted
      const republished = await buildChart({
        name: ociChart,
        version: '0.1.0',
        marker: 'r14-republish',
      });
      await rawUploadBlob(
        layout.repoName,
        admin,
        ociChart,
        republished.tgzBytes,
        republished.tgzDigest,
      );
      await rawUploadBlob(
        layout.repoName,
        admin,
        ociChart,
        Buffer.from('{}'),
        `sha256:${'5'.repeat(64)}`,
      );
      const republishManifest = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: 'application/vnd.oci.image.manifest.v1+json',
          config: { mediaType: 'x', digest: `sha256:${'5'.repeat(64)}`, size: 1 },
          layers: [
            { mediaType: 'x', digest: republished.tgzDigest, size: republished.tgzBytes.length },
          ],
        }),
      );
      const republishPush = await rawPutManifest(
        layout.repoName,
        admin,
        ociChart,
        '0.1.0',
        republishManifest,
        'application/vnd.oci.image.manifest.v1+json',
      );
      expect(
        republishPush.status,
        'republish of same name:version after delete is accepted (old version is gone)',
      ).toBe(201);

      // Verify the republished version is now accessible
      const republishedManifest = await rawGetManifest(layout.repoName, admin, ociChart, '0.1.0');
      expect(republishedManifest.status, 'republished manifest is accessible').toBe(200);
    },
  );
});
