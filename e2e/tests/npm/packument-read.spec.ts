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
 * What the npm registry serves when a client READS, pinned at the protocol level with raw HTTP (no
 * `npm` client), against a real server. What stays here is the compression of a large packument
 * (RPS-1359): MockMvc has no Tomcat connector, so only a real server shows it on the plain port.
 * The abbreviated packument (RPS-1356), what a publish leaves out of the packument (RPS-1357),
 * `HEAD` (RPS-1358), the validators (RPS-1359), undeprecating (RPS-1360) and the header of a tarball
 * download (RPS-1363) are `NpmPackumentReadIT` and `NpmWriteResponseIT` (RPS-2738).
 */
import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';

import { RepoType } from '../../src/api/panel-api.js';
import { env } from '../../src/env.js';
import { repoUrl } from '../../src/repo-url.js';
import {
  adminCredential,
  buildPublishDocument,
  buildTarball,
  encodePackageNameForUrl,
  npmAuthHeader,
  rawPublish,
} from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Published {
  repoName: string;
  name: string;
  version: string;
}

type Manifest = Record<string, unknown>;

async function newRepo(seeder: Seeder): Promise<string> {
  const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
  return repo.name;
}

function urlOf(repoName: string, path: string): string {
  return repoUrl(repoName, path);
}

/**
 * Publishes one version through a raw PUT, the way libnpmpublish sends it. `extra` goes into the
 * version manifest, `topLevel` next to it (npm's own `_from`/`_resolved` are such fields).
 */
async function publish(
  repoName: string,
  name: string,
  version: string,
  options: { extra?: Manifest; topLevel?: Manifest; description?: string; tag?: string } = {},
): Promise<Published> {
  const document = buildPublishDocument({
    repoName,
    packageName: name,
    version,
    tarballBytes: buildTarball({ packageName: name, version }),
    description: options.description,
    tag: options.tag,
  });
  Object.assign((document.versions as Record<string, Manifest>)[version] ?? {}, options.extra);
  Object.assign(document, options.topLevel);
  const res = await rawPublish(repoName, adminCredential(), name, document);
  expect(res.status, `publish ${name}@${version}: ${res.msgId ?? ''}`).toBe(200);
  return { repoName, name, version };
}

type Doc = { versions: Record<string, Manifest> };

/** A packument GET with `Accept-Encoding: <encoding>`, its headers and body exactly as received. */
async function rawExchange(
  repoName: string,
  name: string,
  encoding: string,
): Promise<{ headers: http.IncomingHttpHeaders; body: Buffer }> {
  return new Promise((resolve, reject) => {
    // node:http refuses an https URL, which a TLS stack's repo URL is (README.md "TLS stack").
    const transport = env.repoBaseUrl.startsWith('https:') ? https : http;
    const req = transport.get(
      urlOf(repoName, encodePackageNameForUrl(name)),
      {
        headers: {
          ...npmAuthHeader(adminCredential()),
          Accept: 'application/json',
          'Accept-Encoding': encoding,
        },
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (chunk: Buffer) => chunks.push(chunk));
        res.on('end', () => resolve({ headers: res.headers, body: Buffer.concat(chunks) }));
      },
    );
    req.on('error', reject);
  });
}

test.describe('npm registry reads (raw HTTP)', () => {
  test(
    'a large packument is compressed for a client that accepts it (RPS-1359)',
    { tag: ['@wire'] },
    async ({ seeder }) => {
      const repoName = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-large`;
      const description = 'a long, compressible description. '.repeat(400);
      await publish(repoName, name, '1.0.0', { description });

      // `fetch` unpacks the answer and drops `Content-Encoding`, so the exchange is done at the
      // socket level, where the answer is what the server sent.
      const compressed = await rawExchange(repoName, name, 'gzip');
      expect(compressed.headers['content-encoding']).toBe('gzip');
      expect(String(compressed.headers.vary ?? '')).toMatch(/accept-encoding/i);
      expect(
        (JSON.parse(zlib.gunzipSync(compressed.body).toString('utf8')) as Doc).versions['1.0.0']
          ?.description,
      ).toBe(description);
      expect(compressed.body.length, 'smaller than the document').toBeLessThan(description.length);

      const plain = await rawExchange(repoName, name, 'identity');
      expect(
        plain.headers['content-encoding'],
        'not for a client that does not ask',
      ).toBeUndefined();
      expect((JSON.parse(plain.body.toString('utf8')) as Doc).versions['1.0.0']?.description).toBe(
        description,
      );
    },
  );
});
