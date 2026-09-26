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
 * The `NO_ROUTE` operations of the wire permission matrix (RPS-1475, `scenarios/manage-matrix.ts`):
 * the protocols that have no wire request for removing anything. PyPI (twine has no delete, the
 * upload API is a POST), Go (the GOPROXY protocol is read-only, an upload is a PUT), Maven (`mvn
 * deploy` only PUTs) and Helm's OCI protocol (`helm push` only writes; the classic route's DELETE is
 * `helm-manage.ts`'s MANAGE cell) have no delete handler: removing a file, a module version or an
 * artifact is a panel action (the panel API is outside the wire matrix, RPS-1483).
 *
 * The cell of each is a raw `DELETE` of the file the client would have to remove, with every
 * credential. The router answers a request no handler recognises with a `404 unknownPath` before any
 * credential is looked at, so ALL five credentials get the same 404 (an admin included: there is no
 * route to be allowed on), and the file stays served byte for byte. The status neither depends on
 * the credential nor on whether the file exists, so it does not say whether a package is there.
 */
import { expect } from '@playwright/test';

import { bindPrepared, type ManageOperation } from '../scenarios/manage-catalog.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import { env } from '../env.js';
import { adminCredential, authHeader, sha256Hex } from './raw-http.js';
import * as go from './golang-raw.js';
import * as maven from './maven-raw.js';
import * as pypi from './pypi-raw.js';

/** What the matrix reads back of the one file: its status and digest as admin. */
export interface FileFingerprint {
  status: number;
  sha256?: string;
}

const NO_ROUTE_STATUS = 404;

async function readFile(url: string): Promise<FileFingerprint> {
  const res = await fetch(url, { headers: authHeader(adminCredential()) });
  const bytes = Buffer.from(await res.arrayBuffer());
  return { status: res.status, sha256: res.status === 200 ? sha256Hex(bytes) : undefined };
}

/** A `DELETE` of `url` (the file the client would have to remove) as `credential`. */
async function rawDelete(url: string, credential: MaterializedCredential) {
  const res = await fetch(url, { method: 'DELETE', headers: authHeader(credential) });
  await res.arrayBuffer();
  return res.status;
}

function fileOperation(spec: {
  protocol: string;
  id: string;
  what: string;
  /** Seeds the file (as admin) and returns its URL (with `<repo>` in the repo's place for a command). */
  seed: (repoName: string, runId: string) => Promise<string>;
}): ManageOperation {
  return {
    protocol: spec.protocol,
    id: spec.id,
    permission: 'NO_ROUTE',
    client: `raw DELETE of ${spec.what}`,
    refusedStatus: NO_ROUTE_STATUS,
    async prepare(seeder, repo) {
      const url = await spec.seed(repo.name, seeder.runId);
      const beforeSeed = await readFile(url);
      expect(beforeSeed.status, `seed: ${spec.what} is served`).toBe(200);
      return bindPrepared<FileFingerprint>({
        run: async (credential) => ({
          status: await rawDelete(url, credential),
          command: `DELETE ${url.replace(repo.name, '<repo>')}`,
        }),
        fingerprint: () => readFile(url),
        expectEffect: () => {
          throw new Error(
            `${spec.protocol} has no route to ${spec.id}: no credential may be allowed`,
          );
        },
      });
    },
  };
}

/** PyPI: the wheel file at its stored path (`<name>/-/<file>`, what a project page links to). */
function pypiDeleteFile(): ManageOperation {
  return fileOperation({
    protocol: 'pypi',
    id: 'delete-file',
    what: 'a wheel',
    async seed(repoName, runId) {
      const name = `e2e-${runId}-nr`;
      const wheel = pypi.buildWheel({ name, version: '1.0.0' });
      const res = await pypi.rawUpload(repoName, adminCredential(), wheel);
      expect(res.status, `seed ${wheel.filename}: ${res.msgId ?? ''}`).toBe(200);
      return `${env.repoBaseUrl}/${repoName}/${pypi.downloadPath(name, wheel.filename)}`;
    },
  });
}

/** Go: the module zip of a version (`<module>/@v/<version>.zip`). */
function goDeleteZip(): ManageOperation {
  return fileOperation({
    protocol: 'golang',
    id: 'delete-zip',
    what: 'a module zip',
    async seed(repoName, runId) {
      const built = await go.buildModuleZip({
        modulePath: `${go.MODULE_DOMAIN}/e2e${runId.replace(/[^a-z0-9]/gi, '')}/nr`,
        version: 'v1.0.0',
      });
      const res = await go.rawUpload(repoName, adminCredential(), built);
      expect(res.status, `seed ${built.modulePath}@${built.version}: ${res.msgId ?? ''}`).toBe(200);
      return `${env.repoBaseUrl}/${repoName}/${go.zipRelPath(built.modulePath, built.version)}`;
    },
  });
}

/** Maven: the jar of a release. */
function mavenDeleteJar(): ManageOperation {
  return fileOperation({
    protocol: 'maven',
    id: 'delete-jar',
    what: 'a jar',
    async seed(repoName, runId) {
      const groupId = 'e2e.noroute';
      const artifactId = `nr-${runId}`;
      const version = '1.0.0';
      const relPath = `${maven.versionDir(groupId, artifactId, version)}/${artifactId}-${version}.jar`;
      const res = await maven.rawPut(
        repoName,
        adminCredential(),
        relPath,
        maven.buildJar({ groupId, artifactId, version }),
        'application/java-archive',
      );
      expect(res.status, `seed ${relPath}: ${res.msgId ?? ''}`).toBe(200);
      return `${env.repoBaseUrl}/${repoName}/${relPath}`;
    },
  });
}

export const PYPI_MANAGE_OPERATIONS: readonly ManageOperation[] = [pypiDeleteFile()];
export const GOLANG_MANAGE_OPERATIONS: readonly ManageOperation[] = [goDeleteZip()];
export const MAVEN_MANAGE_OPERATIONS: readonly ManageOperation[] = [mavenDeleteJar()];
