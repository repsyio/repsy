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
 * `npm` client), against a real server: the abbreviated packument (RPS-1356), what a publish leaves
 * out of the packument (RPS-1357), `HEAD` (RPS-1358), the validators and compression of the packument
 * (RPS-1359), undeprecating (RPS-1360) and the header of a tarball download (RPS-1363). The
 * npm-family clients meet the same facts in `tests/npm-clients/matrix/*`; this file is the wire-level
 * proof, with headers the clients hide.
 */
import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';

import { RepoType } from '../../src/api/panel-api.js';
import { env } from '../../src/env.js';
import { repoUrl } from '../../src/repo-url.js';
import { optedIn } from '../../src/stack-overlays.js';
import {
  adminCredential,
  bareName,
  buildPublishDocument,
  buildTarball,
  encodePackageNameForUrl,
  npmAuthHeader,
  rawPublish,
} from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

const ABBREVIATED = 'application/vnd.npm.install-v1+json';
const PNPM_ACCEPT = `${ABBREVIATED}; q=1.0, application/json; q=0.8`;

interface Published {
  repoName: string;
  name: string;
  version: string;
}

type Manifest = Record<string, unknown>;

const admin = adminCredential();

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
  const res = await rawPublish(repoName, admin, name, document);
  expect(res.status, `publish ${name}@${version}: ${res.msgId ?? ''}`).toBe(200);
  return { repoName, name, version };
}

async function packument(
  repoName: string,
  name: string,
  headers: Record<string, string> = {},
  method = 'GET',
): Promise<Response> {
  return fetch(urlOf(repoName, encodePackageNameForUrl(name)), {
    method,
    headers: { ...npmAuthHeader(admin), ...headers },
  });
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
          ...npmAuthHeader(admin),
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

async function versionsOf(res: Response): Promise<Record<string, Manifest>> {
  expect(res.status).toBe(200);
  return ((await res.json()) as { versions: Record<string, Manifest> }).versions;
}

test.describe('npm registry reads (raw HTTP)', () => {
  test(
    'the abbreviated packument carries os, cpu, libc, peerDependenciesMeta and funding (RPS-1356)',
    { tag: ['@abbreviated'] },
    async ({ seeder }) => {
      const repoName = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-abbreviated`;
      const extra = {
        os: ['linux', '!win32'],
        cpu: ['x64'],
        libc: ['glibc'],
        peerDependencies: { react: '*' },
        peerDependenciesMeta: { react: { optional: true } },
        funding: { url: 'https://funding.example/e2e' },
        hasInstallScript: true,
      };
      await publish(repoName, name, '1.0.0', { extra });

      const abbreviated = await packument(repoName, name, { Accept: PNPM_ACCEPT });
      expect(abbreviated.headers.get('content-type')).toContain(ABBREVIATED);
      const full = await packument(repoName, name, { Accept: 'application/json' });
      expect(full.headers.get('content-type')).toContain('application/json');

      for (const served of [await versionsOf(abbreviated), await versionsOf(full)]) {
        expect(served['1.0.0']).toMatchObject(extra);
      }
    },
  );

  test(
    'the abbreviated packument derives hasInstallScript from the scripts of a version (RPS-1390)',
    { tag: ['@abbreviated'] },
    async ({ seeder }) => {
      const repoName = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-install-script`;
      await publish(repoName, name, '1.0.0', { extra: { scripts: { postinstall: 'node x.js' } } });
      await publish(repoName, name, '1.1.0', { extra: { scripts: { test: 'jest' } } });

      const served = await versionsOf(await packument(repoName, name, { Accept: PNPM_ACCEPT }));
      expect(served['1.0.0']?.hasInstallScript).toBe(true);
      expect(served['1.1.0']).not.toHaveProperty('hasInstallScript');
    },
  );

  test(
    'a publish answers a JSON body with ok, id and success, not an empty 200 (RPS-1390)',
    { tag: ['@packument'] },
    async ({ seeder }) => {
      test.fail(
        optedIn('tls'),
        'RPS-1559: the SSL connectors miss EncodedSolidusHandling.DECODE (encoded slash -> bodyless 400)',
      );
      const repoName = await newRepo(seeder);
      const name = `@e2e-${seeder.runId}/publish-body`;
      const res = await rawPublish(
        repoName,
        admin,
        name,
        buildPublishDocument({
          repoName,
          packageName: name,
          version: '1.0.0',
          tarballBytes: buildTarball({ packageName: name, version: '1.0.0' }),
        }),
      );

      expect(res.status).toBe(200);
      expect(JSON.parse(res.body.toString('utf8'))).toEqual({ ok: true, id: name, success: true });
    },
  );

  test(
    "the packument keeps neither the publish's tarball nor the publisher's paths (RPS-1357)",
    { tag: ['@packument'] },
    async ({ seeder }) => {
      const repoName = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-clean`;
      const paths = { _from: 'file:/home/ada/clean.tgz', _resolved: '/home/ada/clean.tgz' };
      await publish(repoName, name, '1.0.0', { extra: paths, topLevel: paths });
      await publish(repoName, name, '1.1.0', { extra: paths, topLevel: paths });
      await publish(repoName, name, '2.0.0-beta.1', { extra: paths, tag: 'next' });

      const res = await packument(repoName, name, { Accept: 'application/json' });
      const document = (await res.json()) as Manifest & { versions: Record<string, Manifest> };
      expect(Object.keys(document.versions)).toEqual(['1.0.0', '1.1.0', '2.0.0-beta.1']);
      expect(document).not.toHaveProperty('_attachments');
      expect(document).not.toHaveProperty('_from');
      expect(document).not.toHaveProperty('_resolved');
      for (const version of Object.values(document.versions)) {
        expect(version).not.toHaveProperty('_from');
        expect(version).not.toHaveProperty('_resolved');
      }
    },
  );

  test(
    'HEAD is 200 for a package and a tarball that exist, 404 for one that does not (RPS-1358)',
    { tag: ['@wire', '@negative'] },
    async ({ seeder }) => {
      test.fail(
        optedIn('tls'),
        'RPS-1559: the SSL connectors miss EncodedSolidusHandling.DECODE (encoded slash -> bodyless 400)',
      );
      const repoName = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-head`;
      const scoped = `@e2e-${seeder.runId}/head`;
      await publish(repoName, name, '1.0.0');
      await publish(repoName, scoped, '1.0.0');
      const headers = npmAuthHeader(admin);
      const head = async (path: string) =>
        (await fetch(urlOf(repoName, path), { method: 'HEAD', headers })).status;
      const get = async (path: string) => (await fetch(urlOf(repoName, path), { headers })).status;

      const cases: [string, number][] = [
        [name, 200],
        [`${name}-never-published`, 404],
        [`${name}/-/${name}-1.0.0.tgz`, 200],
        [`${name}/-/${name}-9.9.9.tgz`, 404],
        [scoped, 200],
        [`@e2e-${seeder.runId}/never-published`, 404],
        [`${scoped}/-/${bareName(scoped)}-1.0.0.tgz`, 200],
        [`${scoped}/-/${bareName(scoped)}-9.9.9.tgz`, 404],
        ['-/ping', 200],
      ];
      for (const [path, status] of cases) {
        expect(await head(path), `HEAD ${path}`).toBe(status);
        expect(await get(path), `GET ${path}, the same answer without the body`).toBe(
          path === '-/ping' ? 200 : status,
        );
      }

      const noRepo = await fetch(repoUrl(`e2e-${seeder.runId}-norepo`, 'x'), {
        method: 'HEAD',
        headers,
      });
      expect(noRepo.status, 'HEAD in a repository that does not exist').toBe(404);
    },
  );

  test(
    'the packument has an ETag and a Last-Modified, varies on Accept and answers a conditional GET with 304 (RPS-1359)',
    { tag: ['@wire'] },
    async ({ seeder }) => {
      const repoName = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-cached`;
      await publish(repoName, name, '1.0.0');

      const weak = /^W\/"[0-9a-f]{64}"$/;
      const full = await packument(repoName, name, { Accept: 'application/json' });
      const abbreviated = await packument(repoName, name, { Accept: PNPM_ACCEPT });
      const fullTag = full.headers.get('etag') ?? '';
      const abbreviatedTag = abbreviated.headers.get('etag') ?? '';
      expect(fullTag).toMatch(weak);
      expect(abbreviatedTag).toMatch(weak);
      expect(abbreviatedTag, 'each of the two documents has its own tag').not.toBe(fullTag);
      expect(full.headers.get('last-modified')).toBeTruthy();
      expect(full.headers.get('vary') ?? '').toMatch(/\baccept\b/i);
      expect(abbreviated.headers.get('vary') ?? '').toMatch(/\baccept\b/i);

      const conditional = (accept: string, headers: Record<string, string>) =>
        packument(repoName, name, { Accept: accept, ...headers });
      const notModified = await conditional(PNPM_ACCEPT, { 'If-None-Match': abbreviatedTag });
      expect(notModified.status, 'If-None-Match with the current tag').toBe(304);
      expect(await notModified.text()).toBe('');
      expect(
        (
          await conditional('application/json', {
            'If-Modified-Since': full.headers.get('last-modified') ?? '',
          })
        ).status,
        'If-Modified-Since with the modification time',
      ).toBe(304);
      expect(
        (await conditional('application/json', { 'If-None-Match': abbreviatedTag })).status,
        'the tag of the other document does not match',
      ).toBe(200);

      await publish(repoName, name, '1.1.0');
      expect(
        (await conditional('application/json', { 'If-None-Match': fullTag })).status,
        'a version was published since',
      ).toBe(200);
    },
  );

  test(
    'a large packument is compressed for a client that accepts it (RPS-1359)',
    { tag: ['@wire'] },
    async ({ seeder }) => {
      test.fail(optedIn('tls'), 'RPS-1559: the SSL connectors get no response compression');
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

  test(
    "undeprecating a version removes its deprecated field, like the registry's own semantics (RPS-1360)",
    { tag: ['@deprecate'] },
    async ({ seeder }) => {
      const repoName = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-deprecate`;
      await publish(repoName, name, '1.0.0');
      await publish(repoName, name, '1.1.0');

      /** What `npm deprecate pkg@x <message>` sends: the packument it read, edited. */
      const deprecate = async (version: string, message: string) => {
        const read = await packument(repoName, name, { Accept: 'application/json' });
        const document = (await read.json()) as Manifest & { versions: Record<string, Manifest> };
        (document.versions[version] as Manifest).deprecated = message;
        const res = await rawPublish(repoName, admin, name, document);
        expect(res.status, `deprecate ${version}: ${res.msgId ?? ''}`).toBe(200);
      };
      const deprecations = async (accept: string) =>
        Object.fromEntries(
          Object.entries(await versionsOf(await packument(repoName, name, { Accept: accept }))).map(
            ([version, manifest]) => [version, manifest.deprecated],
          ),
        );

      await deprecate('1.0.0', 'use 1.1.0');
      await deprecate('1.1.0', 'use 1.2.0');
      expect(await deprecations('application/json')).toEqual({
        '1.0.0': 'use 1.1.0',
        '1.1.0': 'use 1.2.0',
      });

      await deprecate('1.0.0', '');
      for (const accept of ['application/json', PNPM_ACCEPT]) {
        const served = await versionsOf(await packument(repoName, name, { Accept: accept }));
        expect(Object.hasOwn(served['1.0.0'] ?? {}, 'deprecated'), `1.0.0, ${accept}`).toBe(false);
        expect(served['1.1.0']?.deprecated).toBe('use 1.2.0');
      }
    },
  );

  test(
    'a tarball is downloaded as an attachment named after it (RPS-1363)',
    { tag: ['@wire'] },
    async ({ seeder }) => {
      test.fail(
        optedIn('tls'),
        'RPS-1559: the SSL connectors miss EncodedSolidusHandling.DECODE (encoded slash -> bodyless 400)',
      );
      const repoName = await newRepo(seeder);
      const name = `e2e-${seeder.runId}-download`;
      const scoped = `@e2e-${seeder.runId}/download`;
      await publish(repoName, name, '1.0.0');
      await publish(repoName, scoped, '1.0.0');

      for (const [packageName, file] of [
        [name, `${name}-1.0.0.tgz`],
        [scoped, `${bareName(scoped)}-1.0.0.tgz`],
      ] as const) {
        const res = await fetch(urlOf(repoName, `${packageName}/-/${file}`), {
          headers: npmAuthHeader(admin),
        });
        expect(res.status).toBe(200);
        expect(res.headers.get('content-type')).toBe('application/octet-stream');
        expect(res.headers.get('content-disposition')).toBe(`attachment; filename="${file}"`);
      }
    },
  );
});
