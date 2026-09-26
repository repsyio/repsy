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
 * The Helm operation of the wire permission matrix (RPS-1475, `scenarios/manage-matrix.ts`), plus
 * the one Helm route that has no delete at all:
 *
 *  - `delete-chart` is the classic (ChartMuseum) `DELETE /<repo>/api/charts/<name>/<version>`, the only
 *    wire request that removes a chart. It removes stored files: MANAGE
 *    (`AbstractHelmChartDeleteProtocolMethodHandler`), so ADMIN only; a USER account and a deploy token,
 *    read-write or not, are refused with a 401 (RPS-1424). Helm ships no command for it (the
 *    `cm-push` plugin only pushes), so the request is raw: `client: 'raw'`, no client exit code. The
 *    existing single-cell pin is R14 in `tests/helm/registry-rules.spec.ts`; the matrix adds the other
 *    credentials and the "nothing changed" half of every refused cell.
 *  - `delete-oci-manifest` is `DELETE /v2/<repo>/<chart>/manifests/<tag>` on a Helm repo, what an OCI
 *    client's manifest delete (`oras manifest delete`) would send. Helm's OCI protocol has no such
 *    handler: NO_ROUTE. Every credential gets the router's 404 (`unknownPath`, `NAME_UNKNOWN` in an OCI
 *    envelope), whether the chart exists or not, and the chart stays servable.
 */
import { expect } from '@playwright/test';

import { bindPrepared, type ManageOperation } from '../scenarios/manage-catalog.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import type { SeededRepo, Seeder } from '../seed/seeder.js';
import { buildChart } from './helm-chart.js';
import {
  adminCredential,
  authHeader,
  chartFileName,
  classicRepoUrl,
  parseIndex,
  rawDownloadChart,
  rawGetIndex,
  rawGetManifest,
  rawPutManifest,
  rawUploadBlob,
  rawUploadChart,
  sha256Hex,
  v2Url,
  HELM_MEDIA_TYPES,
} from './helm-raw.js';

/** What a cell acts on: one chart of two versions, and the version the operation targets. */
interface HelmSubject {
  repoName: string;
  chart: string;
  /** Stays after a delete of `target`. */
  kept: string;
  /** The version the operation is about. */
  target: string;
}

/** What the matrix reads back of the chart, as admin. */
export interface HelmManageFingerprint {
  indexStatus: number;
  /** The versions of the chart in `index.yaml`, sorted. */
  indexed: string[];
  /** Per seeded version: the sha256 of the classic download, or `status:<n>` when it is not served. */
  downloads: Record<string, string>;
}

async function seedClassic(
  seeder: Seeder,
  repo: SeededRepo,
  operationId: string,
): Promise<HelmSubject> {
  const chart = `e2e-${seeder.runId}-${operationId}`;
  const subject: HelmSubject = { repoName: repo.name, chart, kept: '0.1.0', target: '0.2.0' };
  for (const version of [subject.kept, subject.target]) {
    const built = await buildChart({ name: chart, version, marker: `${operationId}-${version}` });
    const res = await rawUploadChart(
      repo.name,
      adminCredential(),
      built.tgzBytes,
      chartFileName(chart, version),
    );
    expect(res.status, `seed ${chart}-${version}: ${res.msgId ?? ''}`).toBe(201);
  }
  return subject;
}

async function fingerprint(subject: HelmSubject): Promise<HelmManageFingerprint> {
  const admin = adminCredential();
  const index = await rawGetIndex(subject.repoName, admin);
  const indexed =
    index.status === 200
      ? (parseIndex(index.body.toString('utf8')).entries[subject.chart] ?? [])
          .map((e) => e.version)
          .sort()
      : [];
  const downloads: Record<string, string> = {};
  for (const version of [subject.kept, subject.target]) {
    const res = await rawDownloadChart(subject.repoName, admin, subject.chart, version);
    downloads[version] = res.status === 200 ? sha256Hex(res.body) : `status:${res.status}`;
  }
  return { indexStatus: index.status, indexed, downloads };
}

function deleteChart(): ManageOperation {
  return {
    protocol: 'helm',
    id: 'delete-chart',
    permission: 'MANAGE',
    client: 'raw DELETE /<repo>/api/charts/<chart>/<version>',
    async prepare(seeder, repo) {
      const subject = await seedClassic(seeder, repo, 'delete-chart');
      const url = `${classicRepoUrl(subject.repoName)}/api/charts/${subject.chart}/${subject.target}`;
      const request = async (credential: MaterializedCredential) => {
        const res = await fetch(url, { method: 'DELETE', headers: authHeader(credential) });
        await res.arrayBuffer();
        return res.status;
      };
      return bindPrepared<HelmManageFingerprint>({
        run: async (credential) => ({
          status: await request(credential),
          command: `DELETE ${url.replace(subject.repoName, '<repo>')}`,
        }),
        fingerprint: () => fingerprint(subject),
        expectEffect: (before, after) => {
          expect(before.indexed, 'both versions were indexed').toEqual(
            [subject.kept, subject.target].sort(),
          );
          expect(after.indexed, 'the index lost the version').toEqual([subject.kept]);
          expect(after.downloads[subject.target], 'the chart file is gone').toBe('status:404');
          expect(after.downloads[subject.kept], 'the other version is untouched').toBe(
            before.downloads[subject.kept],
          );
        },
      });
    },
  };
}

/** What the OCI operation reads back: the manifest of the chart, served by tag. */
export interface HelmOciFingerprint {
  manifestStatus: number;
  manifestSha256?: string;
}

function deleteOciManifest(): ManageOperation {
  return {
    protocol: 'helm',
    id: 'delete-oci-manifest',
    permission: 'NO_ROUTE',
    client: 'raw DELETE /v2/<repo>/<chart>/manifests/<tag>',
    // The router answers a route no handler recognises with 404, before any credential is looked at:
    // every credential gets the same answer.
    refusedStatus: 404,
    async prepare(seeder, repo) {
      const chart = `e2e-${seeder.runId}-delete-oci`;
      const version = '0.1.0';
      const admin = adminCredential();
      const built = await buildChart({ name: chart, version, marker: 'delete-oci' });
      const config = Buffer.from(
        JSON.stringify({ name: chart, version, apiVersion: 'v2', type: 'application' }),
      );
      const layer = built.tgzBytes;
      for (const [bytes, contentType] of [
        [config, HELM_MEDIA_TYPES.config],
        [layer, HELM_MEDIA_TYPES.layer],
      ] as const) {
        const up = await rawUploadBlob(
          repo.name,
          admin,
          chart,
          bytes,
          `sha256:${sha256Hex(bytes)}`,
          {
            contentType,
          },
        );
        expect(up.status, `seed blob of ${chart}: ${up.msgId ?? ''}`).toBe(201);
      }
      const manifest = Buffer.from(
        JSON.stringify({
          schemaVersion: 2,
          mediaType: HELM_MEDIA_TYPES.manifest,
          config: {
            mediaType: HELM_MEDIA_TYPES.config,
            digest: `sha256:${sha256Hex(config)}`,
            size: config.length,
          },
          layers: [
            {
              mediaType: HELM_MEDIA_TYPES.layer,
              digest: `sha256:${sha256Hex(layer)}`,
              size: layer.length,
            },
          ],
        }),
      );
      const put = await rawPutManifest(
        repo.name,
        admin,
        chart,
        version,
        manifest,
        HELM_MEDIA_TYPES.manifest,
      );
      expect(put.status, `seed manifest of ${chart}: ${put.msgId ?? ''}`).toBe(201);

      const url = v2Url(`/${repo.name}/${chart}/manifests/${version}`);
      return bindPrepared<HelmOciFingerprint>({
        run: async (credential) => {
          const res = await fetch(url, { method: 'DELETE', headers: authHeader(credential) });
          await res.arrayBuffer();
          return {
            status: res.status,
            command: `DELETE ${url.replace(repo.name, '<repo>')}`,
          };
        },
        fingerprint: async () => {
          const res = await rawGetManifest(repo.name, admin, chart, version);
          return {
            manifestStatus: res.status,
            manifestSha256: res.status === 200 ? sha256Hex(res.body) : undefined,
          };
        },
        expectEffect: () => {
          throw new Error('delete-oci-manifest has no route: no credential may be allowed');
        },
      });
    },
  };
}

/** The Helm operations of the manage matrix (`tests/helm/manage-matrix.spec.ts`). */
export const HELM_MANAGE_OPERATIONS: readonly ManageOperation[] = [
  deleteChart(),
  deleteOciManifest(),
];
