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
 * `@trivy` (RPS-1484): the REAL `repsy-scanner-trivy` (`./run.sh local up --trivy`, README.md "Real
 * scanner stack"), the one place its image and its Trivy binary run in the e2e, so the stub scanner the
 * `@scanner` specs use cannot drift from what the real service does.
 *
 *  1. The raw contract calls the stub is held to as well (`src/stubs/scanner/contract.ts`, also run
 *     against the stub by `tests/skeleton/scanner-stub.spec.ts`): the health check, the 401, the 400s,
 *     the 415, the 404 and the shape of a job.
 *  2. One real scan through the whole path, no stub anywhere: an npm package published to Repsy that
 *     BUNDLES `lodash@4.17.20` (`node_modules/lodash/package.json` in the tarball) is scanned by Trivy
 *     and the panel shows CVE-2021-23337 (HIGH, fixed in 4.17.21) on it. A `package-lock.json` in the
 *     tarball would not do: the scanner runs `trivy rootfs` on the extracted tarball, which reads the
 *     installed packages of a `node_modules`, not lockfiles (probed live, Trivy 0.66.0).
 *  3. The same for a Docker image, scanned BY REFERENCE: the backend hands the scanner
 *     `repsy:9090/<repo>/<image>:<tag>` and a registry token, and Trivy pulls the image from Repsy
 *     itself (`DOCKER_INTERNAL_REGISTRY_BASE_URL` of the overlay).
 *
 * Needs the network: Trivy keeps its vulnerability database in a volume of the stack and downloads it
 * when the scanner starts (readiness holds until that is done, bounded here by `READY_TIMEOUT_MS`). A
 * red run whose failure message mentions the download ("failed to download", "OCI artifact error",
 * "unexpected status code") is ghcr.io / mirror.gcr.io not answering, not a Repsy fault (README).
 */
import zlib from 'node:zlib';

import { RepoType, type PanelApi, type VulnerabilityScanInfo } from '../../src/api/panel-api.js';
import { OCI_MEDIA_TYPES, buildTar } from '../../src/clients/docker-image.js';
import {
  adminCredential,
  imageRef,
  rawPutManifest,
  rawUploadBlob,
  sha256Hex,
} from '../../src/clients/docker-raw.js';
import { buildPublishDocument, rawPublish } from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { optedIn } from '../../src/stack-overlays.js';
import {
  CONTRACT_CASES,
  expectFindingShape,
  expectJobShape,
  postScan,
  uniqueScanId,
  untilFinished,
  type ContractTarget,
} from '../../src/stubs/scanner/contract.js';
import { DEFAULT_SCANNER_API_KEY } from '../../src/stubs/scanner/client.js';
import { SCANNER_VERSION } from '../../src/stubs/scanner/rules.js';

const TARGET: ContractTarget = {
  base: (process.env.REPSY_SCANNER_STUB_URL || 'http://localhost:8090').replace(/\/+$/, ''),
  apiKey: process.env.REPSY_SCANNER_API_KEY || DEFAULT_SCANNER_API_KEY,
};

/** The first start downloads two databases (about 1.4 GB on disk) before the scanner is ready. */
const READY_TIMEOUT_MS = 300_000;
/** A scan of a small package: Trivy on a directory, seconds; generous for a slow disk or a late download. */
const SCAN_TIMEOUT_MS = 240_000;

const LODASH = { name: 'lodash', version: '4.17.20', cve: 'CVE-2021-23337' };

test.describe('the real repsy-scanner-trivy', { tag: ['@trivy'] }, () => {
  test.skip(
    !optedIn('trivy'),
    'opt-in: needs the real scanner (./run.sh local up --trivy); run with REPSY_E2E_TRIVY=1 ./run.sh test (README "Real scanner stack")',
  );
  test.describe.configure({ timeout: 360_000 });

  test.beforeAll(async () => {
    test.setTimeout(READY_TIMEOUT_MS + 30_000);
    await expect
      .poll(
        async () => {
          try {
            const response = await fetch(`${TARGET.base}/actuator/health/readiness`, {
              signal: AbortSignal.timeout(5_000),
            });
            return response.status;
          } catch {
            return 0;
          }
        },
        {
          message:
            `the scanner at ${TARGET.base} is ready (it holds readiness while Trivy downloads its ` +
            'vulnerability databases; start the stack with ./run.sh local up --trivy, README "Real scanner stack")',
          timeout: READY_TIMEOUT_MS,
          intervals: [1_000, 2_000, 5_000],
        },
      )
      .toBe(200);
  });

  test.describe('the contract the stub scanner mimics', () => {
    for (const contract of CONTRACT_CASES) {
      test(contract.name, async () => {
        await contract.run(TARGET);
      });
    }
  });

  test.describe('a real scan', () => {
    test('a scan with findings is COMPLETED with findings of the contract shape, and a scanner version', async () => {
      const scanId = uniqueScanId('lodash');
      const accepted = await postScan(
        TARGET,
        { scanId, artifactName: 'bundled', artifactVersion: '1.0.0' },
        bundledLodashTarball('bundled', '1.0.0'),
        'bundled-1.0.0.tgz',
      );
      expect(accepted.status).toBe(200);

      const done = await untilFinished(TARGET, scanId, SCAN_TIMEOUT_MS);
      expect(done.status, `scan: ${done.errorMessage}`).toBe('COMPLETED');
      expectJobShape(done, scanId);
      const findings = done.result?.findings ?? [];
      for (const finding of findings) {
        expectFindingShape(finding);
      }
      expect(findings.find((finding) => finding.cveId === LODASH.cve)).toMatchObject({
        severity: 'HIGH',
        packageName: LODASH.name,
        packageVersion: LODASH.version,
        fixedVersion: '4.17.21',
        fixStatus: 'FIXED',
      });
    });

    test('an npm package that bundles lodash 4.17.20 is scanned by Trivy: CVE-2021-23337 in the panel', async ({
      panelApi,
      seeder,
    }) => {
      const repo = await seeder.createRepo(RepoType.NPM);
      const name = `e2e-${seeder.runId}-bundled`;
      const version = '1.0.0';
      const tarball = bundledLodashTarball(name, version);

      const published = await rawPublish(
        repo.name,
        adminCredential(),
        name,
        buildPublishDocument({
          repoName: repo.name,
          packageName: name,
          version,
          tarballBytes: tarball,
        }),
      );
      expect(published.status, `publish: ${published.body.toString('utf8')}`).toBeLessThan(300);

      const scan = await finishedScan(panelApi, repo.name, name, version);
      expect(scan, 'the scan of the published tarball').toMatchObject({
        repoType: RepoType.NPM,
        status: 'COMPLETED',
        highestSeverity: 'HIGH',
        scannerName: 'trivy',
      });
      // The stub reports SCANNER_VERSION; the real service never does (whatever it reports).
      expect(scan.scannerVersion).not.toBe(SCANNER_VERSION);

      const findings = await panelApi.listScanFindings(repo.name, scan.id ?? '');
      const finding = findings.find((candidate) => candidate.cveId === LODASH.cve);
      expect(
        findings.map((candidate) => candidate.cveId),
        `${LODASH.name}@${LODASH.version} has ${LODASH.cve}`,
      ).toContain(LODASH.cve);
      expect(finding).toMatchObject({
        severity: 'HIGH',
        packageName: LODASH.name,
        packageVersion: LODASH.version,
        fixedVersion: '4.17.21',
        fixStatus: 'FIXED',
      });
    });

    test('a Docker image is pulled from Repsy by the scanner and scanned: the same CVE', async ({
      panelApi,
      seeder,
    }) => {
      const repo = await seeder.createRepo(RepoType.DOCKER);
      const image = `e2e-${seeder.runId}-img`;
      const tag = '1.0.0';
      const built = buildLodashImage();
      const credential = adminCredential();

      for (const [label, bytes, digest] of [
        ['layer', built.layerBytes, built.layerDigest],
        ['config', built.configBytes, built.configDigest],
      ] as const) {
        const uploaded = await rawUploadBlob(repo.name, credential, image, bytes, digest);
        expect(uploaded.status, `upload of the ${label} blob`).toBe(201);
      }
      const pushed = await rawPutManifest(
        repo.name,
        credential,
        image,
        tag,
        built.manifestBytes,
        OCI_MEDIA_TYPES.manifest,
      );
      expect(pushed.status, `manifest push (${imageRef(repo.name, image, tag)})`).toBe(201);

      const scan = await finishedScan(panelApi, repo.name, image, tag);
      expect(scan, 'the scan of the pushed image').toMatchObject({
        repoType: RepoType.DOCKER,
        status: 'COMPLETED',
      });
      expect(scan.scannerVersion).not.toBe(SCANNER_VERSION);
      const findings = await panelApi.listScanFindings(repo.name, scan.id ?? '');
      expect(
        findings.map((candidate) => candidate.cveId),
        `an image with ${LODASH.name}@${LODASH.version} in its node_modules has ${LODASH.cve}`,
      ).toContain(LODASH.cve);
    });
  });
});

/** An npm tarball of `name@version` that bundles lodash 4.17.20 in its `node_modules`. */
function bundledLodashTarball(name: string, version: string): Buffer {
  return zlib.gzipSync(
    buildTar([
      {
        name: 'package/package.json',
        data: json({ name, version, bundleDependencies: [LODASH.name] }),
      },
      {
        name: `package/node_modules/${LODASH.name}/package.json`,
        data: json({ name: LODASH.name, version: LODASH.version }),
      },
    ]),
  );
}

function json(value: unknown): Buffer {
  return Buffer.from(JSON.stringify(value), 'utf8');
}

/** The newest scan of a version once it is FINISHED (COMPLETED or FAILED); a FAILED one fails the spec with why. */
async function finishedScan(
  panelApi: PanelApi,
  repoName: string,
  artifactName: string,
  version: string,
): Promise<VulnerabilityScanInfo> {
  let newest: VulnerabilityScanInfo | undefined;
  await expect
    .poll(
      async () => {
        newest = (await panelApi.listVersionScans(repoName, artifactName, version))[0];
        return ['COMPLETED', 'FAILED'].includes(newest?.status ?? '');
      },
      {
        message: `the newest scan of ${artifactName}@${version} in ${repoName} finished`,
        timeout: SCAN_TIMEOUT_MS,
        intervals: [1_000, 2_000, 5_000],
      },
    )
    .toBe(true);
  expect(newest?.status, `scan failed: ${newest?.errorMessage}`).toBe('COMPLETED');
  return newest as VulnerabilityScanInfo;
}

/** An OCI image with one layer that holds `app/node_modules/lodash/package.json` (lodash 4.17.20). */
function buildLodashImage(): {
  layerBytes: Buffer;
  layerDigest: string;
  configBytes: Buffer;
  configDigest: string;
  manifestBytes: Buffer;
} {
  const tar = buildTar([
    {
      name: `app/node_modules/${LODASH.name}/package.json`,
      data: json({ name: LODASH.name, version: LODASH.version }),
    },
  ]);
  const layerBytes = zlib.gzipSync(tar);
  const layerDigest = `sha256:${sha256Hex(layerBytes)}`;
  const configBytes = json({
    architecture: 'amd64',
    os: 'linux',
    config: {},
    rootfs: { type: 'layers', diff_ids: [`sha256:${sha256Hex(tar)}`] },
    history: [{ created: '2026-01-01T00:00:00Z', created_by: 'repsy-e2e' }],
  });
  const configDigest = `sha256:${sha256Hex(configBytes)}`;
  const manifestBytes = json({
    schemaVersion: 2,
    mediaType: OCI_MEDIA_TYPES.manifest,
    config: { mediaType: OCI_MEDIA_TYPES.config, digest: configDigest, size: configBytes.length },
    layers: [{ mediaType: OCI_MEDIA_TYPES.layer, digest: layerDigest, size: layerBytes.length }],
  });
  return { layerBytes, layerDigest, configBytes, configDigest, manifestBytes };
}
