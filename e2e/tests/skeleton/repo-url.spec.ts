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

import fs from 'node:fs/promises';
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import os from 'node:os';
import path from 'node:path';

import { expect, test } from '@playwright/test';

import { pushRuby } from '../../src/clients/oversize.js';
import { goProxyUrlFor } from '../../src/clients/golang-tls-shim.js';
import { writeNpmrc } from '../../src/clients/npm-family/config.js';
import { imageRef, pushScope } from '../../src/clients/docker-raw.js';
import { env } from '../../src/env.js';
import {
  imageRef as sharedImageRef,
  repoPath,
  repoUrl,
  v2RepoUrl,
  v2Url,
} from '../../src/repo-url.js';
import type { World } from '../../src/scenarios/world.js';
import { capabilitiesFor, target, type UrlScheme } from '../../src/target.js';

/**
 * The owner-aware URL helper (`src/repo-url.ts`, RPS-1492): with `urlScheme=repo` every URL is what
 * it always was, with `urlScheme=owner-repo` and `REPSY_REPO_OWNER=x` every repository URL is
 * `<base>/x/<repo>/...`, in the helpers, in the command line a real client is given, in a raw probe and
 * in the config files the harness writes. No stack is needed: a stub HTTP server stands in for the
 * repository port and a fake `gem` for the client.
 */
const BASE = 'http://repsy.e2e.test:9090';

/** Runs `body` with `env.repoBaseUrl`, `env.repoOwner` and `target.urlScheme` set, restored after. */
async function withScheme<T>(
  scheme: UrlScheme,
  owner: string | undefined,
  body: () => Promise<T> | T,
  repoBaseUrl: string = BASE,
): Promise<T> {
  const saved = { base: env.repoBaseUrl, owner: env.repoOwner, scheme: target.urlScheme };
  env.repoBaseUrl = repoBaseUrl;
  env.repoOwner = owner;
  target.urlScheme = scheme;
  try {
    return await body();
  } finally {
    env.repoBaseUrl = saved.base;
    env.repoOwner = saved.owner;
    target.urlScheme = saved.scheme;
  }
}

test('every target keeps the repo scheme unless REPSY_E2E_URL_SCHEME says otherwise', () => {
  expect(capabilitiesFor('local').urlScheme).toBe('repo');
  expect(capabilitiesFor('ci').urlScheme).toBe('repo');
  expect(capabilitiesFor('remote').urlScheme).toBe('repo');
});

test('urlScheme=repo > paths and URLs are <base>/<repo>, whatever the owner setting', async () => {
  await withScheme('repo', 'x', () => {
    expect(repoPath('r1')).toBe('r1');
    expect(repoUrl('r1')).toBe(`${BASE}/r1`);
    expect(repoUrl('r1', '')).toBe(`${BASE}/r1/`);
    expect(repoUrl('r1', 'v3/index.json')).toBe(`${BASE}/r1/v3/index.json`);
    expect(sharedImageRef('r1', 'img', 'v1')).toBe('repsy.e2e.test:9090/r1/img:v1');
    expect(v2Url('/')).toBe(`${BASE}/v2/`);
    expect(v2RepoUrl('r1', 'img/manifests/v1')).toBe(`${BASE}/v2/r1/img/manifests/v1`);
  });
});

test('urlScheme=owner-repo, REPSY_REPO_OWNER=x > paths and URLs are <base>/x/<repo>', async () => {
  await withScheme('owner-repo', 'x', () => {
    expect(repoPath('r1')).toBe('x/r1');
    expect(repoUrl('r1')).toBe(`${BASE}/x/r1`);
    expect(repoUrl('r1', '')).toBe(`${BASE}/x/r1/`);
    expect(repoUrl('r1', 'v3/index.json')).toBe(`${BASE}/x/r1/v3/index.json`);
    expect(imageRef('r1', 'img', 'v1')).toBe('repsy.e2e.test:9090/x/r1/img:v1');
    expect(pushScope('r1', 'img')).toBe('repository:x/r1/img:push,pull');
    expect(v2RepoUrl('r1', 'img/manifests/v1')).toBe(`${BASE}/v2/x/r1/img/manifests/v1`);
    // Registry-wide paths name no repository, so no owner.
    expect(v2Url('/_catalog')).toBe(`${BASE}/v2/_catalog`);
  });
});

test('urlScheme=owner-repo without an owner fails loudly instead of building a repo URL', async () => {
  await withScheme('owner-repo', undefined, () => {
    expect(() => repoPath('r1')).toThrow(/REPSY_REPO_OWNER/);
    expect(() => repoUrl('r1')).toThrow(/REPSY_REPO_OWNER/);
  });
});

test('config the harness writes carries the owner: .npmrc and GOPROXY', async () => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'repo-url-npmrc-'));
  const anonymous = {};
  const token = {
    transport: 'basic' as const,
    username: 't',
    password: 'secret',
    kind: 'token' as const,
  };
  try {
    for (const [scheme, owner, segment] of [
      ['repo', undefined, 'r1'],
      ['owner-repo', 'x', 'x/r1'],
    ] as const) {
      await withScheme(scheme, owner, async () => {
        const npmrc = await fs.readFile(
          await writeNpmrc(home, [{ repoName: 'r1', credential: token }]),
          'utf8',
        );
        expect(npmrc).toContain(`registry=${BASE}/${segment}/`);
        expect(npmrc).toContain(`//repsy.e2e.test:9090/${segment}/:_authToken=secret`);

        // No credential: no TLS shim, so the URL is built right here.
        const go = await goProxyUrlFor('r1', anonymous);
        expect(go.proxyUrl).toBe(`${BASE}/${segment}`);
      });
    }
  } finally {
    await fs.rm(home, { recursive: true, force: true });
  }
});

test('the recorded client command and the raw probe carry x/<repo> under owner-repo', async () => {
  // A fake `gem` that succeeds, ahead of any real one on PATH, and a stub of the repository port.
  const bin = await fs.mkdtemp(path.join(os.tmpdir(), 'repo-url-bin-'));
  await fs.writeFile(path.join(bin, 'gem'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  const seen: string[] = [];
  const server = http.createServer((req, res) => {
    seen.push(`${req.method} ${req.url}`);
    req.resume();
    res.writeHead(201).end();
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const stub = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const savedPath = process.env.PATH;
  process.env.PATH = `${bin}${path.delimiter}${savedPath ?? ''}`;

  const world = {
    repoName: 'r1',
    credential: {
      transport: 'basic',
      username: 'e2euser',
      password: 'S3cret-repo-url',
      kind: 'password',
    },
    publishTarget: { packageName: 'e2e-repo-url', version: '1.0.0' },
  } as unknown as World;

  try {
    for (const [scheme, owner, segment] of [
      ['repo', undefined, 'r1'],
      ['owner-repo', 'x', 'x/r1'],
    ] as const) {
      seen.length = 0;
      const pushed = await withScheme(
        scheme,
        owner,
        () => pushRuby(world, 0, `repo-url-${scheme}`),
        stub,
      );
      expect(pushed.command).toContain(`--host ${stub}/${segment}`);
      expect(seen).toEqual([`POST /${segment}/api/v1/gems`]);
    }
  } finally {
    process.env.PATH = savedPath;
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(bin, { recursive: true, force: true });
  }
});
