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
 * uv as a PyPI client (RPS-1486): what only a real `uv` shows, next to the catalog
 * (`uv-catalog.spec.ts`) that runs the shared auth/override scenarios through it. Every case runs the
 * real `uv` in a private HOME (`src/clients/uv.ts`), against a fresh private repo.
 *
 * Probed live (uv 0.12.19) before any assertion was written:
 *
 *  - U1 `uv publish` sends `sha256_digest` (and `blake2_256_digest`), so RPS-1224's
 *    `sha256DigestMissing` refusal never applies to it: it is accepted like twine. Its form is
 *    captured off a local server, not just inferred from the repo accepting it.
 *  - U2 a `uv publish` of a wheel and an sdist in one call stores both; `uv lock` records both files
 *    with the sha256 the index advertised (= the published bytes), the registry URL without any
 *    credential, and `uv sync --locked` installs them.
 *  - U3 uv authenticates with its own mechanisms: the named-index variables (the catalog), a
 *    `~/.netrc` (here), never a credential in `pyproject.toml`/`uv.lock`. An anonymous lock against a
 *    private repo is a 401 hint, not a silent success. (uv's keyring provider needs the `keyring`
 *    executable, which the runner does not carry: not covered.)
 *  - U4 `uv pip install --index-url` (credentials in the URL), `--require-hashes` (a wrong hash is a
 *    hash mismatch and installs nothing, an unpinned requirement is refused) and `uv pip compile
 *    --generate-hashes` (the published sha256).
 *  - U5 a `uv.lock` whose hash was edited is refused by `uv sync` ("Hash mismatch"), which is what
 *    makes `resolve`'s "the lock's hash is the installed bytes" sound.
 *  - U6 PEP 691: uv asks the simple index for `application/vnd.pypi.simple.v1+json` first (with the
 *    HTML types as fallbacks); Repsy has no JSON simple API and answers 200 `text/html` to that
 *    header and to a JSON-only one. Pinned as observed: uv falls back to the HTML page.
 *  - U7 `uv publish --check-url`: a file already stored with the same bytes is skipped (exit 0, no
 *    upload, even on a no-override repo); the same name with other bytes is refused by uv before it
 *    uploads. Given no credential in the check URL, uv cannot read a private index and uploads anyway
 *    (then the repo's own `403 fileAlreadyExists` applies).
 *  - U8 a deleted release (PyPI has no wire delete, the panel is the only way, RPS-1226 for HEAD): the
 *    file and page 404 (HEAD too), `uv lock` finds no solution, an old lock's `uv sync --frozen`
 *    fails on the 404, and a re-publish makes the same lock work again. A ranged GET of a wheel
 *    answers 206 with the right slice.
 *  - U9 (pip, for parity) `pip download --require-hashes`: right hash passes, wrong hash fails.
 */
import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { pipEnv, pypiAdapter } from '../../src/clients/pypi.js';
import {
  adminCredential,
  authHeader,
  buildSdist,
  buildWheel,
  distName,
  downloadPath,
  indexUrlFor,
  msgIdOf,
  parseSimplePage,
  rawDownload,
  rawGetSimplePage,
  rawHead,
  rawUpload,
  sha256Hex,
  simplePagePath,
  uploadUrl,
  wheelFilename,
  type BuiltWheel,
} from '../../src/clients/pypi-raw.js';
import {
  parseUvLock,
  prepareConsumer,
  publishWithUv,
  runUv,
  simpleIndexUrl,
} from '../../src/clients/uv.js';
import { env } from '../../src/env.js';
import { repoPath, repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Scenario } from '../../src/scenarios/types.js';
import type { World } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

const UV_TAG = ['@uv'];
const NONE = { transport: undefined } as const;

interface Layout {
  repoName: string;
  packageName: string;
  version: string;
  world: World;
}

/** A fresh private PyPI repo, a run-unique package and an admin-password `World` for it. */
async function newLayout(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
  const packageName = `e2e-${seeder.runId}-uv-${label}`;
  const version = pypiAdapter.version('release');
  const scenario: Scenario = {
    id: `uv-${label}`,
    tags: [],
    repo: { privateRepo: true },
    credential: 'admin-password',
    expect: { publish: 'ok', consume: 'ok' },
  };
  const target = { packageName, version };
  const world: World = {
    scenario,
    protocol: 'pypi',
    repoName: repo.name,
    credential: adminCredential(),
    publishTarget: target,
    consumeTarget: target,
  };
  return { repoName: repo.name, packageName, version, world };
}

/** Seeds the wheel with a raw upload (not the client under test). */
async function seedWheel(layout: Layout, tag = 'seed'): Promise<BuiltWheel> {
  const built = buildWheel({
    name: layout.packageName,
    version: layout.version,
    marker: `${tag}-${layout.repoName}`,
  });
  const res = await rawUpload(layout.repoName, adminCredential(), built);
  expect(res.status, `seed upload: ${msgIdOf(res.body) ?? ''}`).toBe(200);
  return built;
}

async function writeDist(
  work: string,
  files: { name: string; bytes: Buffer }[],
): Promise<string[]> {
  const dir = path.join(work, 'dist');
  await fs.mkdir(dir, { recursive: true });
  const paths: string[] = [];
  for (const file of files) {
    const target = path.join(dir, file.name);
    await fs.writeFile(target, file.bytes);
    paths.push(target);
  }
  return paths;
}

/** What one request to the capture server looked like. */
interface Captured {
  method: string;
  url: string;
  headers: IncomingHttpHeaders;
  form?: FormData;
}

/** A local HTTP server that records every request and answers 200 to a POST, 404 to anything else:
 *  what a client sends, seen on the wire. */
async function startCapture(): Promise<{
  base: string;
  requests: Captured[];
  close: () => Promise<void>;
}> {
  const requests: Captured[] = [];
  const server: Server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => {
      void (async () => {
        const captured: Captured = {
          method: req.method ?? '',
          url: req.url ?? '',
          headers: req.headers,
        };
        const contentType = req.headers['content-type'] ?? '';
        if (req.method === 'POST' && contentType.startsWith('multipart/form-data')) {
          captured.form = await new Response(Buffer.concat(chunks), {
            headers: { 'content-type': contentType },
          }).formData();
        }
        requests.push(captured);
        res.statusCode = req.method === 'POST' ? 200 : 404;
        res.end();
      })();
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  return {
    base: `http://127.0.0.1:${port}`,
    requests,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

/** The `user` of a captured `Authorization: Basic` header (never the password). */
function basicUser(headers: IncomingHttpHeaders): string | undefined {
  const value = headers.authorization;
  if (!value?.startsWith('Basic ')) {
    return undefined;
  }
  return Buffer.from(value.slice('Basic '.length), 'base64').toString('utf8').split(':')[0];
}

/** `.venv/lib/pythonX.Y/site-packages` of a project `uv sync` created. */
async function sitePackages(project: string): Promise<string> {
  const lib = path.join(project, '.venv', 'lib');
  const [python] = await fs.readdir(lib);
  return path.join(lib, python ?? '', 'site-packages');
}

async function exists(file: string): Promise<boolean> {
  try {
    await fs.stat(file);
    return true;
  } catch {
    return false;
  }
}

test.describe('pypi uv client', () => {
  test(
    'U1 uv publish sends sha256_digest and its own form fields, with a password or a token ' +
      'credential from its environment (RPS-1224)',
    { tag: UV_TAG },
    async ({ seeder }) => {
      const layout = await newLayout(seeder, 'form');
      const capture = await startCapture();
      try {
        const wheel = buildWheel({ name: layout.packageName, version: layout.version });
        const sdist = buildSdist({ name: layout.packageName, version: layout.version });
        const { home, work } = await isolatedWorkDir('uv-form');
        const files = await writeDist(work, [
          { name: wheel.filename, bytes: wheel.bytes },
          { name: sdist.filename, bytes: sdist.bytes },
        ]);

        const password = {
          transport: 'basic' as const,
          username: 'e2e-user',
          password: 'e2e-pw-1',
          kind: 'password' as const,
        };
        const token = {
          transport: 'basic' as const,
          username: 'ignored',
          password: 'rdt-e2e-token',
          kind: 'token' as const,
        };
        for (const credential of [password, token]) {
          const result = await runUv(
            'uv-form',
            [
              'publish',
              '--trusted-publishing',
              'never',
              '--publish-url',
              `${capture.base}/repo/`,
              ...files,
            ],
            { home, cwd: work, credential, mode: 'publish' },
          );
          expect(result.exitCode, result.command).toBe(0);
        }

        const posts = capture.requests.filter((r) => r.method === 'POST');
        expect(posts, 'two uploads (wheel + sdist) per credential').toHaveLength(4);
        expect(
          posts.map((p) => p.url),
          'uv posts to the publish URL as given',
        ).toEqual(['/repo/', '/repo/', '/repo/', '/repo/']);
        expect(
          posts.map((p) => basicUser(p.headers)),
          'a password credential is sent as its username; a token as uv’s fixed __token__',
        ).toEqual(['e2e-user', 'e2e-user', '__token__', '__token__']);
        expect(String(posts[0]?.headers['user-agent']), 'uv identifies itself').toMatch(/^uv\/\d/);

        for (const post of posts.slice(0, 2)) {
          const form = post.form;
          const file = form?.get('content');
          expect(file, 'the file part is named content').toBeInstanceOf(File);
          const name = (file as File).name;
          const built = name === wheel.filename ? wheel : sdist;
          expect(name, 'the part carries the archive filename').toBe(built.filename);
          expect(
            form?.get('sha256_digest'),
            `sha256_digest of ${name} (RPS-1224: never missing)`,
          ).toBe(built.sha256Hex);
          expect(String(form?.get('blake2_256_digest')), 'uv also sends blake2_256_digest').toMatch(
            /^[0-9a-f]{64}$/,
          );
          expect(form?.get(':action')).toBe('file_upload');
          expect(form?.get('protocol_version')).toBe('1');
          expect(String(form?.get('name')).replace(/[-_.]+/g, '-')).toBe(layout.packageName);
          expect(form?.get('version')).toBe(layout.version);
          expect(form?.get('requires_python')).toBe('>=3.9');
          expect(form?.get('filetype')).toBe(built === wheel ? 'bdist_wheel' : 'sdist');
        }
      } finally {
        await capture.close();
      }
    },
  );

  test(
    'U2 a wheel and an sdist published by one uv publish are both locked with their published ' +
      'sha256, the lock names no credential, and uv sync installs the wheel',
    { tag: UV_TAG },
    async ({ seeder }) => {
      const layout = await newLayout(seeder, 'lock');
      const wheel = buildWheel({ name: layout.packageName, version: layout.version });
      const sdist = buildSdist({ name: layout.packageName, version: layout.version });
      const { home, work } = await isolatedWorkDir('uv-lock-publish');
      const files = await writeDist(work, [
        { name: wheel.filename, bytes: wheel.bytes },
        { name: sdist.filename, bytes: sdist.bytes },
      ]);
      const credential = layout.world.credential;
      const published = await runUv(
        'uv-lock-publish',
        [
          'publish',
          '--trusted-publishing',
          'never',
          '--publish-url',
          uploadUrl(layout.repoName),
          ...files,
        ],
        { home, cwd: work, credential, mode: 'publish' },
      );
      expect(published.exitCode, published.command).toBe(0);

      const page = await rawGetSimplePage(layout.repoName, credential, layout.packageName);
      const links = parseSimplePage(page.body);
      expect(links.map((l) => [l.filename, l.sha256]).sort()).toEqual(
        [
          [wheel.filename, wheel.sha256Hex],
          [sdist.filename, sdist.sha256Hex],
        ].sort(),
      );

      const consumer = await prepareConsumer(
        'uv-lock-consume',
        layout.repoName,
        layout.packageName,
        layout.version,
      );
      const lock = await runUv('uv-lock', ['lock'], {
        home: consumer.home,
        cwd: consumer.project,
        credential,
        mode: 'index',
      });
      expect(lock.exitCode, lock.command).toBe(0);

      const lockText = await fs.readFile(path.join(consumer.project, 'uv.lock'), 'utf8');
      const locked = parseUvLock(lockText).find((p) => p.name === layout.packageName);
      expect(locked, 'the package is in uv.lock').toBeDefined();
      expect(locked?.version).toBe(layout.version);
      expect(locked?.source, 'the registry, with no credential in it').toBe(
        simpleIndexUrl(layout.repoName),
      );
      const base = repoUrl(layout.repoName);
      expect(
        [...(locked?.files ?? [])].sort((a, b) => a.url.localeCompare(b.url)),
        'every file locked with the canonical download URL and the published sha256',
      ).toEqual(
        [
          {
            url: `${base}/${downloadPath(layout.packageName, wheel.filename)}`,
            sha256: wheel.sha256Hex,
          },
          {
            url: `${base}/${downloadPath(layout.packageName, sdist.filename)}`,
            sha256: sdist.sha256Hex,
          },
        ].sort((a, b) => a.url.localeCompare(b.url)),
      );
      const pyproject = await fs.readFile(path.join(consumer.project, 'pyproject.toml'), 'utf8');
      for (const text of [lockText, pyproject]) {
        expect(text, 'no credential in the files uv writes').not.toContain(
          credential.password ?? '',
        );
        expect(text).not.toContain('@localhost');
      }

      const sync = await runUv('uv-sync', ['sync', '--locked'], {
        home: consumer.home,
        cwd: consumer.project,
        credential,
        mode: 'index',
      });
      expect(sync.exitCode, sync.command).toBe(0);
      const marker = path.join(
        await sitePackages(consumer.project),
        distName(layout.packageName),
        'e2e_marker.txt',
      );
      expect(await exists(marker), 'the wheel’s files are installed').toBe(true);
    },
  );

  test(
    'U3 uv reads its credential from ~/.netrc, and an anonymous lock of a private repo is a 401 hint',
    { tag: [...UV_TAG, '@auth'] },
    async ({ seeder }) => {
      const layout = await newLayout(seeder, 'netrc');
      await seedWheel(layout);
      const admin = adminCredential();

      const withNetrc = await prepareConsumer(
        'uv-netrc',
        layout.repoName,
        layout.packageName,
        layout.version,
      );
      await fs.writeFile(
        path.join(withNetrc.home, '.netrc'),
        `machine ${new URL(env.repoBaseUrl).hostname} login ${admin.username} password ${admin.password}\n`,
        { mode: 0o600 },
      );
      const ok = await runUv('uv-netrc', ['lock'], {
        home: withNetrc.home,
        cwd: withNetrc.project,
        credential: NONE,
        mode: 'none',
      });
      expect(ok.exitCode, `lock with a netrc: ${ok.command}`).toBe(0);

      const anon = await prepareConsumer(
        'uv-anon',
        layout.repoName,
        layout.packageName,
        layout.version,
      );
      const refused = await runUv('uv-anon', ['lock'], {
        home: anon.home,
        cwd: anon.project,
        credential: NONE,
        mode: 'none',
      });
      expect(refused.exitCode, 'no credential, private repo').not.toBe(0);
      expect(refused.stderr, 'uv names the 401 it got').toContain('401 Unauthorized');
      expect(await exists(path.join(anon.project, 'uv.lock')), 'nothing was locked').toBe(false);
    },
  );

  test(
    'U4 uv pip install --index-url, --require-hashes and pip compile --generate-hashes',
    { tag: UV_TAG },
    async ({ seeder }) => {
      const layout = await newLayout(seeder, 'pipinstall');
      const built = await seedWheel(layout);
      const credential = layout.world.credential;
      const { home, work } = await isolatedWorkDir('uv-pip');
      const uv = (label: string, args: string[]) =>
        runUv(`uv-pip-${label}`, args, { home, cwd: work, credential, mode: 'none' });
      const indexUrl = indexUrlFor(layout.repoName, credential);
      const requirement = `${layout.packageName}==${layout.version}`;
      const installed = (venv: string) =>
        exists(path.join(work, venv, 'lib')).then(async (has) => {
          if (!has) {
            return false;
          }
          const [python] = await fs.readdir(path.join(work, venv, 'lib'));
          return exists(
            path.join(
              work,
              venv,
              'lib',
              python ?? '',
              'site-packages',
              distName(layout.packageName),
              'e2e_marker.txt',
            ),
          );
        });

      expect((await uv('venv-a', ['venv', 'venv-a'])).exitCode).toBe(0);
      const install = await uv('install', [
        'pip',
        'install',
        '--python',
        'venv-a/bin/python',
        '--index-url',
        indexUrl,
        requirement,
      ]);
      expect(install.exitCode, install.command).toBe(0);
      expect(await installed('venv-a'), 'the wheel is installed').toBe(true);

      await fs.writeFile(
        path.join(work, 'good.txt'),
        `${requirement} --hash=sha256:${built.sha256Hex}\n`,
      );
      expect((await uv('venv-b', ['venv', 'venv-b'])).exitCode).toBe(0);
      const good = await uv('good', [
        'pip',
        'install',
        '--python',
        'venv-b/bin/python',
        '--index-url',
        indexUrl,
        '--require-hashes',
        '-r',
        'good.txt',
      ]);
      expect(good.exitCode, good.command).toBe(0);
      expect(await installed('venv-b')).toBe(true);

      await fs.writeFile(
        path.join(work, 'bad.txt'),
        `${requirement} --hash=sha256:${'0'.repeat(64)}\n`,
      );
      expect((await uv('venv-c', ['venv', 'venv-c'])).exitCode).toBe(0);
      const bad = await uv('bad', [
        'pip',
        'install',
        '--python',
        'venv-c/bin/python',
        '--index-url',
        indexUrl,
        '--require-hashes',
        '-r',
        'bad.txt',
      ]);
      expect(bad.exitCode, 'a wrong hash is refused').not.toBe(0);
      expect(bad.stderr).toContain('Hash mismatch');
      expect(bad.stderr, 'uv computed the served file’s hash').toContain(
        `sha256:${built.sha256Hex}`,
      );
      expect(await installed('venv-c'), 'nothing was installed').toBe(false);

      await fs.writeFile(path.join(work, 'unpinned.txt'), `${layout.packageName}\n`);
      const unpinned = await uv('unpinned', [
        'pip',
        'install',
        '--python',
        'venv-c/bin/python',
        '--index-url',
        indexUrl,
        '--require-hashes',
        '-r',
        'unpinned.txt',
      ]);
      expect(unpinned.exitCode, 'an unpinned requirement in --require-hashes mode').not.toBe(0);
      expect(unpinned.stderr).toContain('must have their versions pinned');

      await fs.writeFile(path.join(work, 'in.txt'), `${layout.packageName}\n`);
      const compiled = await uv('compile', [
        'pip',
        'compile',
        '--index-url',
        indexUrl,
        '--generate-hashes',
        'in.txt',
      ]);
      expect(compiled.exitCode, compiled.command).toBe(0);
      expect(compiled.stdout).toContain(`${requirement}`);
      expect(compiled.stdout, 'the published sha256 is what --generate-hashes writes').toContain(
        `--hash=sha256:${built.sha256Hex}`,
      );
    },
  );

  test(
    'U5 a uv.lock with an edited hash is refused by uv sync, and nothing is installed',
    { tag: [...UV_TAG, '@negative'] },
    async ({ seeder }) => {
      const layout = await newLayout(seeder, 'tamper');
      const built = await seedWheel(layout);
      const credential = layout.world.credential;
      const consumer = await prepareConsumer(
        'uv-tamper',
        layout.repoName,
        layout.packageName,
        layout.version,
      );
      const opts = {
        home: consumer.home,
        cwd: consumer.project,
        credential,
        mode: 'index',
      } as const;
      expect((await runUv('uv-tamper-lock', ['lock'], opts)).exitCode).toBe(0);

      const lockFile = path.join(consumer.project, 'uv.lock');
      const lockText = await fs.readFile(lockFile, 'utf8');
      expect(lockText).toContain(`sha256:${built.sha256Hex}`);
      await fs.writeFile(lockFile, lockText.replace(built.sha256Hex, '0'.repeat(64)));

      const sync = await runUv('uv-tamper-sync', ['sync', '--frozen'], opts);
      expect(sync.exitCode, 'the served bytes do not match the lock').not.toBe(0);
      expect(sync.stderr).toContain('Hash mismatch');
      expect(sync.stderr, 'uv computed the real hash').toContain(`sha256:${built.sha256Hex}`);
      expect(
        await exists(
          path.join(
            await sitePackages(consumer.project).catch(() => consumer.project),
            distName(layout.packageName),
          ),
        ),
        'nothing installed',
      ).toBe(false);
    },
  );

  test(
    'U6 uv asks for the PEP 691 JSON simple API; the server answers HTML to it, to a JSON-only ' +
      'Accept as well, and uv falls back to the page',
    { tag: UV_TAG },
    async ({ seeder }) => {
      const layout = await newLayout(seeder, 'pep691');
      const built = await seedWheel(layout);
      const credential = layout.world.credential;

      // What uv sends: read off the wire, not assumed.
      const capture = await startCapture();
      let uvAccept: string;
      try {
        const consumer = await prepareConsumer(
          'uv-accept',
          layout.repoName,
          layout.packageName,
          layout.version,
        );
        const pyproject = path.join(consumer.project, 'pyproject.toml');
        const text = await fs.readFile(pyproject, 'utf8');
        await fs.writeFile(
          pyproject,
          text.replace(simpleIndexUrl(layout.repoName), `${capture.base}/x/simple/`),
        );
        await runUv('uv-accept', ['lock'], {
          home: consumer.home,
          cwd: consumer.project,
          credential: NONE,
          mode: 'none',
        });
        const get = capture.requests.find(
          (r) => r.method === 'GET' && r.url.startsWith('/x/simple/'),
        );
        expect(get, 'uv requested the project page').toBeDefined();
        uvAccept = String(get?.headers.accept);
      } finally {
        await capture.close();
      }
      expect(uvAccept, 'uv prefers the JSON simple API').toContain(
        'application/vnd.pypi.simple.v1+json',
      );
      expect(uvAccept, 'with HTML fallbacks').toContain('text/html');

      const url = repoUrl(layout.repoName, simplePagePath(layout.packageName));
      for (const accept of [uvAccept, 'application/vnd.pypi.simple.v1+json']) {
        const res = await fetch(url, { headers: { ...authHeader(credential), Accept: accept } });
        expect(res.status, `Accept: ${accept}`).toBe(200);
        expect(
          res.headers.get('content-type'),
          'the page is HTML whatever the Accept says',
        ).toContain('text/html');
        const links = parseSimplePage(Buffer.from(await res.arrayBuffer()));
        expect(links.map((l) => l.sha256)).toEqual([built.sha256Hex]);
      }
    },
  );

  test(
    'U7 uv publish --check-url skips a file already stored with the same bytes, refuses other ' +
      'bytes before uploading, and cannot read a private index without a credential in the URL',
    { tag: [...UV_TAG, '@settings'] },
    async ({ seeder, panelApi }) => {
      const layout = await newLayout(seeder, 'checkurl');
      const built = await seedWheel(layout);
      await panelApi.updateSettings(layout.repoName, { privateRepo: true, allowOverride: false });
      const admin = layout.world.credential;
      const host = new URL(env.repoBaseUrl);
      const checkUrl = `${host.protocol}//${admin.username}:${encodeURIComponent(admin.password ?? '')}@${host.host}/${repoPath(layout.repoName)}/simple/`;
      const { home, work } = await isolatedWorkDir('uv-checkurl');
      const publish = async (wheel: BuiltWheel, check: string | undefined) => {
        const [file] = await writeDist(work, [{ name: wheel.filename, bytes: wheel.bytes }]);
        return runUv(
          'uv-checkurl',
          [
            'publish',
            '--trusted-publishing',
            'never',
            '--publish-url',
            uploadUrl(layout.repoName),
            ...(check ? ['--check-url', check] : []),
            file ?? '',
          ],
          { home, cwd: work, credential: admin, mode: 'publish', extraEnv: {} },
        );
      };
      const secrets = (text: string) =>
        expect(text, 'the check URL password is redacted').not.toContain(admin.password ?? '');
      const stored = async () =>
        sha256Hex(
          (await rawDownload(layout.repoName, admin, layout.packageName, built.filename)).body,
        );

      const same = await publish(built, checkUrl);
      expect(same.exitCode, `identical bytes: ${same.command}`).toBe(0);
      expect(same.stdout + same.stderr).toContain('already exists, skipping');
      secrets(same.command);

      const other = buildWheel({
        name: layout.packageName,
        version: layout.version,
        marker: 'other-bytes',
      });
      expect(other.sha256Hex).not.toBe(built.sha256Hex);
      const mismatch = await publish(other, checkUrl);
      expect(mismatch.exitCode, 'other bytes under the same name').not.toBe(0);
      expect(mismatch.stderr).toContain('Local file and index file do not match');
      secrets(mismatch.stderr);

      const blind = await publish(built, repoUrl(layout.repoName, 'simple/'));
      expect(
        blind.exitCode,
        'no credential in the check URL: uv uploads and the repo refuses',
      ).not.toBe(0);
      expect(blind.stderr).toContain('403');
      expect(blind.stderr).toContain('fileAlreadyExists');

      expect(await stored(), 'the stored wheel never changed').toBe(built.sha256Hex);
    },
  );

  test(
    'U8 a deleted release: file and page 404 (HEAD too), uv lock has no solution, an old lock fails ' +
      'on the 404 and works again after a re-publish; a ranged GET answers 206 (RPS-1226)',
    { tag: [...UV_TAG, '@negative'] },
    async ({ seeder, panelApi }) => {
      const layout = await newLayout(seeder, 'deleted');
      const built = await seedWheel(layout);
      const credential = layout.world.credential;
      const filePath = downloadPath(layout.packageName, built.filename);

      const url = repoUrl(layout.repoName, filePath);
      const ranged = await fetch(url, {
        headers: { ...authHeader(credential), Range: 'bytes=0-9' },
      });
      expect(ranged.status, 'a ranged GET of the wheel').toBe(206);
      expect(ranged.headers.get('content-range')).toBe(`bytes 0-9/${built.bytes.length}`);
      expect(Buffer.from(await ranged.arrayBuffer()).equals(built.bytes.subarray(0, 10))).toBe(
        true,
      );
      expect(
        (await rawHead(layout.repoName, credential, filePath)).status,
        'HEAD of the stored file',
      ).toBe(200);

      const consumer = await prepareConsumer(
        'uv-deleted',
        layout.repoName,
        layout.packageName,
        layout.version,
      );
      const opts = {
        home: consumer.home,
        cwd: consumer.project,
        credential,
        mode: 'index',
      } as const;
      expect((await runUv('uv-deleted-lock', ['lock'], opts)).exitCode).toBe(0);

      await panelApi.deletePypiRelease(layout.repoName, layout.packageName, layout.version);
      expect(
        (await rawDownload(layout.repoName, credential, layout.packageName, built.filename)).status,
      ).toBe(404);
      expect(
        (await rawHead(layout.repoName, credential, filePath)).status,
        'HEAD of the deleted file',
      ).toBe(404);

      const frozen = await runUv('uv-deleted-frozen', ['sync', '--frozen'], opts);
      expect(frozen.exitCode, 'the locked file is gone').not.toBe(0);
      expect(frozen.stderr).toContain('404');

      const fresh = await prepareConsumer(
        'uv-deleted-fresh',
        layout.repoName,
        layout.packageName,
        layout.version,
      );
      const relock = await runUv('uv-deleted-relock', ['lock'], {
        home: fresh.home,
        cwd: fresh.project,
        credential,
        mode: 'index',
      });
      expect(relock.exitCode, 'no version left to lock').not.toBe(0);
      expect(relock.stderr).toContain('No solution found');

      const republished = await publishWithUv(layout.world, 'uv-deleted-republish');
      expect(republished.exitCode, republished.command).toBe(0);
      const synced = await runUv('uv-deleted-sync', ['sync', '--frozen'], opts);
      // The old lock names the OLD wheel's hash; the re-published wheel has a fresh marker, so uv
      // refuses it: a deleted-then-republished file is a different file to a lock.
      expect(synced.exitCode, 'a re-published wheel has other bytes than the locked one').not.toBe(
        0,
      );
      expect(synced.stderr).toContain('Hash mismatch');
      const relocked = await runUv('uv-deleted-relock2', ['lock', '--upgrade'], opts);
      expect(relocked.exitCode, relocked.command).toBe(0);
      expect((await runUv('uv-deleted-resync', ['sync', '--locked'], opts)).exitCode).toBe(0);
    },
  );

  test(
    'U9 pip download --require-hashes: the published sha256 passes, another is refused',
    { tag: [...UV_TAG] },
    async ({ seeder }) => {
      const layout = await newLayout(seeder, 'piphash');
      const built = await seedWheel(layout);
      const credential = layout.world.credential;
      const { home, work } = await isolatedWorkDir('uv-piphash');
      const pip = (requirements: string, dest: string) =>
        run(
          'python3',
          [
            '-m',
            'pip',
            'download',
            '--no-deps',
            '--no-cache-dir',
            '--require-hashes',
            '-r',
            requirements,
            '--dest',
            dest,
          ],
          {
            cwd: work,
            env: pipEnv(home, credential, layout.repoName),
            timeoutMs: 120_000,
            redact: [credential.password ?? '', encodeURIComponent(credential.password ?? '')],
            label: 'uv-piphash',
          },
        );
      const requirement = `${layout.packageName}==${layout.version}`;
      await fs.writeFile(
        path.join(work, 'good.txt'),
        `${requirement} --hash=sha256:${built.sha256Hex}\n`,
      );
      const good = await pip('good.txt', path.join(work, 'good'));
      expect(good.exitCode, good.command).toBe(0);
      expect(
        await exists(path.join(work, 'good', wheelFilename(layout.packageName, layout.version))),
      ).toBe(true);

      await fs.writeFile(
        path.join(work, 'bad.txt'),
        `${requirement} --hash=sha256:${'0'.repeat(64)}\n`,
      );
      const bad = await pip('bad.txt', path.join(work, 'bad'));
      expect(bad.exitCode, 'a wrong hash').not.toBe(0);
    },
  );
});
