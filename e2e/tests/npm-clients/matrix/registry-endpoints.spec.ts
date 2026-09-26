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
 * Matrix rows 2 and 10 plus `ping` and `search` (RPS-1330): the registry-level endpoints beyond
 * publish/install, now that Repsy answers them (RPS-1329: `GET /-/whoami`, `GET /-/ping`,
 * `GET /-/v1/search`, and the audit endpoints `POST /-/npm/v1/security/advisories/bulk`,
 * `/-/npm/v1/security/audits/quick` and `/-/npm/v1/security/audits`; they used to be 404). Per client
 * with the command, against a real repository of the stack:
 *
 *  - `whoami` needs credentials even on a PUBLIC repository (anonymous is 401 with a Basic
 *    challenge) and answers the username the credential resolves to: `admin` for the admin's
 *    password, the deploy token's own generated username for a token.
 *  - `ping` succeeds on a private repository with a token.
 *  - `search` finds a package of THIS repository by a free-text term, never another repository's, and
 *    answers `[]` with exit 0 for a term nothing matches. The qualifiers (`author:`, `maintainer:`,
 *    `is:`/`not:`; RPS-1343) go through the client's own text too, and the raw probes of them and of
 *    `size`/`from` (RPS-1344) are in `tests/npm/search-token-dist-tags.spec.ts`.
 *  - `logout` (npm, pnpm; RPS-1361) revokes the token of a login (`DELETE /-/user/token/<token>`): the
 *    command exits 0 and the token is refused afterwards.
 *  - `audit` of an installed tree exits 0 with no vulnerabilities and the client really asked the
 *    registry (the wire recorder sees the audit POST answered 200). The stack's scanner is off, so
 *    the report is empty by design (README "Auditing npm packages"); no scanner is needed.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { npmAuthHeader, rawLogin, rawRequestPath } from '../../../src/clients/npm-raw.js';
import type { ClientId, NpmFamilyClient } from '../../../src/clients/npm-family/client.js';
import {
  adminBinding,
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { clientsWith, ENABLED_CLIENTS } from '../../../src/clients/npm-family/registry.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { env } from '../../../src/env.js';
import { repoPath, repoUrl } from '../../../src/repo-url.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';

/**
 * The two totals of an `audit --json` report. npm 7+ counts `metadata.vulnerabilities.total` and
 * `metadata.dependencies.total`; pnpm answers the npm 6 shape (`advisories`, and per-severity
 * counts with `metadata.totalDependencies`).
 */
function auditSummary(json: string): { vulnerabilities: number; dependencies: number } {
  const report = JSON.parse(json) as {
    metadata?: {
      vulnerabilities?: Record<string, number>;
      dependencies?: number | { total?: number };
      totalDependencies?: number;
    };
  };
  const counts = report.metadata?.vulnerabilities ?? {};
  const dependencies = report.metadata?.dependencies;
  return {
    vulnerabilities:
      counts.total ??
      (Object.keys(counts).length > 0
        ? Object.values(counts).reduce((sum, count) => sum + count, 0)
        : -1),
    dependencies:
      (typeof dependencies === 'object' ? dependencies.total : undefined) ??
      report.metadata?.totalDependencies ??
      -1,
  };
}

/**
 * The username a client's `whoami` printed. Berry (no `--json` here) wraps its answer in its own
 * report: `➤ YN0000: <name>` and a `Done in` line.
 */
const WHOAMI_USER: Partial<Record<ClientId, (stdout: string) => string>> = {
  'yarn-berry': (stdout) => /YN0000: (\S+)/.exec(stdout)?.[1] ?? '',
};

function whoamiUser(client: NpmFamilyClient, stdout: string): string {
  return (WHOAMI_USER[client.id] ?? ((text: string) => text.trim()))(stdout);
}

for (const client of clientsWith('whoamiCmd')) {
  test(
    `${client.label} whoami names the credential's user`,
    {
      tag: [client.tag, '@whoami'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const token = await tokenBinding(seeder, repo.name, { readOnly: true });

      const asAdmin = await client.prepare('whoami-admin', [adminBinding(repo.name)]);
      const admin = await client.whoami?.(asAdmin);
      expect(admin?.exitCode, `whoami (admin password): ${admin?.command}\n${admin?.stderr}`).toBe(
        0,
      );
      expect(whoamiUser(client, admin?.stdout ?? '')).toBe(env.adminUsername);

      const asToken = await client.prepare('whoami-token', [token]);
      const named = await client.whoami?.(asToken);
      expect(named?.exitCode, `whoami (deploy token): ${named?.command}\n${named?.stderr}`).toBe(0);
      expect(whoamiUser(client, named?.stdout ?? ''), "the deploy token's own username").toBe(
        token.credential.username,
      );
    },
  );
}

for (const client of ENABLED_CLIENTS.filter((candidate) => candidate.logout)) {
  test(
    `${client.label} logout revokes the token of a login, which stops working`,
    {
      tag: [client.tag, '@logout'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      // The token `npm login` stores: what the registry issues for a name and password.
      const login = await rawLogin(repo.name, env.adminUsername, env.adminPassword);
      expect(login.status, 'the login the token comes from').toBe(201);
      const token = login.token ?? '';
      const ctx = await client.prepare('logout', [
        {
          repoName: repo.name,
          credential: {
            transport: 'basic',
            username: env.adminUsername,
            password: token,
            kind: 'token',
          },
        },
      ]);

      const before = await client.whoami?.(ctx);
      expect(before?.exitCode, `whoami: ${before?.command}\n${before?.stderr}`).toBe(0);

      const loggedOut = await client.logout?.(ctx);
      expect(
        loggedOut?.exitCode,
        `logout: ${loggedOut?.command}\n${loggedOut?.stdout}\n${loggedOut?.stderr} (RPS-1361)`,
      ).toBe(0);

      const after = await rawRequestPath(repo.name, 'GET', '-/whoami', {
        Authorization: `Bearer ${token}`,
      });
      expect(after.status, 'the revoked token is refused from then on').toBe(401);
    },
  );
}

test(
  'whoami needs credentials even on a public repository (raw)',
  {
    tag: ['@npm', '@whoami', '@negative'],
  },
  async ({ seeder }) => {
    const publicRepo = await newRepo(seeder, false);
    const url = repoUrl(publicRepo.name, '-/whoami');

    const anonymous = await fetch(url);
    expect(anonymous.status, 'anonymous whoami on a public repository').toBe(401);
    expect(anonymous.headers.get('www-authenticate')).toMatch(/^Basic realm=/);

    const authenticated = await fetch(url, { headers: npmAuthHeader(adminCredential()) });
    expect(authenticated.status).toBe(200);
    expect(await authenticated.json()).toEqual({ username: env.adminUsername });
  },
);

for (const client of clientsWith('pingCmd')) {
  test(
    `${client.label} ping reaches a private repository`,
    {
      tag: [client.tag, '@ping'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const ctx = await client.prepare('ping', [
        await tokenBinding(seeder, repo.name, { readOnly: true }),
      ]);

      const pinged = await client.ping?.(ctx);
      expect(pinged?.exitCode, `ping: ${pinged?.command}\n${pinged?.stderr}`).toBe(0);
    },
  );
}

for (const client of clientsWith('searchCmd')) {
  test(
    `${client.label} search finds this repository's packages only`,
    {
      tag: [client.tag, '@search'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const other = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await client.prepare('search', [writer]);
      const term = `zz${seeder.runId}`;
      const found = packageNameFor(seeder, `${term}-widget`);

      const published = await publishPackage(client, ctx, {
        packageName: found,
        version: '1.0.0',
        manifest: { description: `the ${term} widget`, keywords: [term] },
      });
      expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

      // The same term in another repository, which the search of this one must never show.
      const otherCtx = await client.prepare('search-other', [
        await tokenBinding(seeder, other.name, { readOnly: false }),
      ]);
      const otherPublished = await publishPackage(client, otherCtx, {
        packageName: packageNameFor(seeder, `${term}-elsewhere`),
        version: '1.0.0',
      });
      expect(otherPublished.result.exitCode).toBe(0);

      const searched = await client.search?.(ctx, term);
      expect(searched?.exitCode, `search: ${searched?.command}\n${searched?.stderr}`).toBe(0);
      const results = JSON.parse(searched?.stdout ?? '[]') as Array<{
        name: string;
        version: string;
      }>;
      expect(results.map((entry) => entry.name)).toEqual([found]);
      expect(results[0]?.version).toBe('1.0.0');

      const nothing = await client.search?.(ctx, `nomatch${seeder.runId}`);
      expect(nothing?.exitCode, 'a search nothing matches still succeeds').toBe(0);
      expect(JSON.parse(nothing?.stdout ?? 'null')).toEqual([]);
    },
  );
}

for (const client of clientsWith('searchCmd')) {
  test(
    `${client.label} search filters on the author qualifier, and a qualifier-only text finds nothing`,
    {
      tag: [client.tag, '@search'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await client.prepare('search-author', [writer]);
      const author = `zz${seeder.runId}author`;
      const mine = packageNameFor(seeder, 'authored');
      const other = packageNameFor(seeder, 'anonymous');

      const first = await publishPackage(client, ctx, {
        packageName: mine,
        version: '1.0.0',
        manifest: { author: { name: author } },
      });
      expect(first.result.exitCode, `publish: ${first.result.command}`).toBe(0);
      const second = await publishPackage(client, ctx, { packageName: other, version: '1.0.0' });
      expect(second.result.exitCode, `publish: ${second.result.command}`).toBe(0);

      const searched = await client.search?.(ctx, `author:${author}`);
      expect(searched?.exitCode, `search: ${searched?.command}\n${searched?.stderr}`).toBe(0);
      const names = (JSON.parse(searched?.stdout ?? '[]') as Array<{ name: string }>).map(
        (entry) => entry.name,
      );
      expect(names, 'only the package of that author').toEqual([mine]);

      const nothing = await client.search?.(ctx, 'is:shiny');
      expect(nothing?.exitCode, `search: ${nothing?.command}\n${nothing?.stderr}`).toBe(0);
      expect(
        JSON.parse(nothing?.stdout ?? 'null'),
        'a text of qualifiers Repsy cannot filter on matches no package (RPS-1343)',
      ).toEqual([]);
    },
  );
}

/**
 * A client's audit report as the two totals. Berry's report is its own text ("No audit suggestions"
 * for a clean tree; its `--json` prints nothing at all) and has no dependency count: the wire check
 * below is what proves it audited the tree.
 */
function summaryOf(
  client: NpmFamilyClient,
  stdout: string,
): { vulnerabilities: number; dependencies: number } {
  return client.id === 'yarn-berry'
    ? { vulnerabilities: stdout.includes('No audit suggestions') ? 0 : -1, dependencies: 1 }
    : auditSummary(stdout);
}

for (const client of clientsWith('auditCmd')) {
  test(
    `${client.label} audit of an installed tree reports nothing and exits 0`,
    {
      tag: [client.tag, '@audit'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const publisher = await client.prepare('audit-pub', [writer]);
      const lib = packageNameFor(seeder, 'audited');
      const published = await publishPackage(client, publisher, {
        packageName: lib,
        version: '1.0.0',
      });
      expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

      // Through the recorder, so the audit request itself is seen. Its URLs (the lockfile's
      // `resolved`) then name the recorder, which is fine for an audit of this throw-away tree.
      const recorder = await startWireRecorder({ rewriteTarballUrls: true });
      try {
        const consumer = await client.prepare('audit-con', [
          {
            ...(await tokenBinding(seeder, repo.name, { readOnly: true })),
            baseUrl: recorder.baseUrl,
          },
        ]);
        await renderConsumer(consumer.work, 'audit-consumer', { [lib]: '1.0.0' });
        const installed = await client.install(consumer, { frozen: false });
        expect(installed.exitCode, `install: ${installed.command}\n${installed.stderr}`).toBe(0);
        expect(
          (await fs.readdir(path.join(consumer.work))).includes(client.lockfile ?? ''),
          'the install wrote a lockfile the audit reads',
        ).toBe(true);

        const audited = await client.audit?.(consumer);
        expect(audited?.exitCode, `audit: ${audited?.command}\n${audited?.stderr}`).toBe(0);
        const { vulnerabilities, dependencies } = summaryOf(client, audited?.stdout ?? '{}');
        expect(vulnerabilities, 'no vulnerabilities').toBe(0);
        expect(dependencies, 'the audited tree had the dependency in it').toBe(1);

        const posts = recorder.entries.filter(
          (entry) => entry.method === 'POST' && entry.path.includes('/-/npm/v1/security/'),
        );
        expect(posts.length, 'the client asked the registry for an audit').toBeGreaterThan(0);
        for (const post of posts) {
          expect(
            post.path.startsWith(`/${repoPath(repo.name)}/-/npm/v1/security/`),
            'of THIS repository',
          ).toBe(true);
          expect(post.status, `${post.method} ${post.path}`).toBe(200);
        }
      } finally {
        await recorder.stop();
      }
    },
  );
}

test(
  'the audit endpoints answer an empty report for a package version nothing is known about (raw)',
  {
    tag: ['@npm', '@audit'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const headers = { ...npmAuthHeader(adminCredential()), 'Content-Type': 'application/json' };
    const base = repoUrl(repo.name, '-/npm/v1/security');

    const bulk = await fetch(`${base}/advisories/bulk`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ 'left-pad': ['1.3.0'] }),
    });
    expect(bulk.status, 'advisories/bulk').toBe(200);
    expect(await bulk.json()).toEqual({});

    const anonymous = await fetch(`${base}/advisories/bulk`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 'left-pad': ['1.3.0'] }),
    });
    expect(anonymous.status, 'a private repository still needs credentials for an audit').toBe(401);
  },
);
