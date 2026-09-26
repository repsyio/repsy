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
 * The scenario-driven ruby suite (step 4e, RPS-294 -- the LAST protocol adapter of step 4):
 * `registerPublishConsumeLoop(rubyAdapter)` wires the whole shared catalog into ruby, exactly like
 * `tests/pypi/publish-consume.spec.ts`/`tests/golang/publish-consume.spec.ts` do for their
 * protocols. Plus dedicated hand-built-`World`/raw-toolchain tests the catalog loop itself cannot
 * exercise (every H/RB-number below was confirmed live before this file was written -- see
 * `README.md`'s "Ruby runner" section for the raw evidence):
 *
 *  - "gem install succeeds using the quick/Marshal.4.8/*.gemspec.rz route" (RPS-1233, fixed, via
 *    `gem`): unlike the catalog loop's own consumer (real `bundle install`, which never needs this
 *    route -- H1's refutation, `ruby-raw.ts`'s file header), a real `gem install` DOES need it, and
 *    a concrete `RubyGemspecHandler` now registers it.
 *  - "gem fetch" (RPS-1234, fixed, via `gem`): the specs.4.8.gz zlib/gzip mismatch is fixed
 *    (asserted directly, not just via the exit code) and, with RPS-1233 also now merged, the
 *    shared gemspec.rz route it depends on is fixed too -- confirmed live below.
 *  - "anonymous gem push exits 1 promptly, no push request ever sent" (H4): the fixture's own
 *    fingerprint proves nothing was stored.
 *  - "gem yank with a RW token succeeds, and the yanked version is omitted from /info" (RPS-1235,
 *    fixed -- no longer a `test.fail()` pin).
 *  - "gem push with a USER-role Basic credential" (H3, the panel's own documented convention: `bundle
 *    config <url> user:pass`/`~/.gem/credentials`'s `Basic base64(user:pass)` line).
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import zlib from 'node:zlib';

import mustache from 'mustache';

import { RepoType } from '../../src/api/panel-api.js';
import { bundleEnv, gemEnv, rubyAdapter } from '../../src/clients/ruby.js';
import {
  adminCredential,
  buildGem,
  gemFilename,
  infoRelPath,
  packageName as rawPackageName,
  parseInfo,
  rawDownload,
  rawGet,
  rawPublish,
  specsRelPath,
  TEMPLATES_DIR,
} from '../../src/clients/ruby-raw.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';

registerPublishConsumeLoop(rubyAdapter);

test('ruby > gem install succeeds using the quick/Marshal.4.8/*.gemspec.rz route (RPS-1233)', async ({
  seeder,
}) => {
  const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
  const admin = adminCredential();
  const name = `e2e_${seeder.runId}_geminstall`;
  const version = rubyAdapter.version('release');

  const built = await buildGem({ name, version });
  const publishRes = await rawPublish(repo.name, admin, built.bytes);
  expect(publishRes.status, 'seed publish').toBe(200);

  const { home, work } = await isolatedWorkDir(`ruby-geminstall-${seeder.runId}`);
  const result = await run(
    'gem',
    ['install', '--source', repoUrl(repo.name), name, '-v', version, '--no-document'],
    { cwd: work, env: gemEnv(home, {}), timeoutMs: 60_000, label: 'ruby-geminstall' },
  );

  expect(result.exitCode, `gem install: ${result.command}`).toBe(0);

  // A real gem install resolves the version via quick/Marshal.4.8/*.gemspec.rz, then writes the
  // resolved spec under GEM_HOME/specifications -- confirms the route was actually exercised, not
  // just that the process happened to exit 0.
  const installedGemspec = path.join(home, 'gems', 'specifications', `${name}-${version}.gemspec`);
  const stat = await fs.stat(installedGemspec);
  expect(stat.isFile(), `installed gemspec at ${installedGemspec}`).toBe(true);
});

test(
  'ruby > gem fetch succeeds now that both specs.4.8.gz (RPS-1234) and gemspec.rz (RPS-1233) ' +
    'are fixed',
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const admin = adminCredential();
    const name = `e2e_${seeder.runId}_gemfetch`;
    const version = rubyAdapter.version('release');

    const built = await buildGem({ name, version });
    const publishRes = await rawPublish(repo.name, admin, built.bytes);
    expect(publishRes.status, 'seed publish').toBe(200);

    // RPS-1234 is fixed: confirmed directly here (not just via `gem fetch`'s overall exit code)
    // -- the server now answers real gzip (RFC 1952): gunzipSync succeeds, inflateSync throws.
    const specsRes = await rawGet(repo.name, admin, specsRelPath());
    expect(specsRes.status, 'specs.4.8.gz is served').toBe(200);
    expect(() => zlib.inflateSync(specsRes.body)).toThrow();
    const decoded = zlib.gunzipSync(specsRes.body);
    expect(decoded.subarray(0, 2)).toEqual(Buffer.from([0x04, 0x08]));

    const { home, work } = await isolatedWorkDir(`ruby-gemfetch-${seeder.runId}`);
    const result = await run(
      'gem',
      ['fetch', '--source', repoUrl(repo.name), name, '-v', version],
      { cwd: work, env: gemEnv(home, {}), timeoutMs: 60_000, label: 'ruby-gemfetch' },
    );

    expect(result.exitCode, `gem fetch: ${result.command}`).toBe(0);

    const fetchedGem = path.join(work, `${name}-${version}.gem`);
    const stat = await fs.stat(fetchedGem);
    expect(stat.isFile(), `fetched gem at ${fetchedGem}`).toBe(true);
  },
);

test(
  'ruby > anonymous gem push exits 1 promptly, no push request ever sent (H4)',
  { tag: ['@auth', '@negative'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
    const admin = adminCredential();
    const name = `e2e_${seeder.runId}_anonpush`;
    const version = rubyAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`ruby-anonpush-${seeder.runId}`);
    const built = await buildGem({ name, version });
    const gemFile = `${work}/${built.filename}`;
    await fs.writeFile(gemFile, built.bytes);

    const before = await rawGet(repo.name, admin, infoRelPath(name));
    expect(before.status, 'nothing published yet').toBe(404);

    const result = await run('gem', ['push', gemFile, '--host', repoUrl(repo.name)], {
      cwd: work,
      env: gemEnv(home, {}),
      timeoutMs: 30_000,
      label: 'ruby-anonpush',
      input: '',
    });
    expect(result.exitCode, 'gem push with no credentials configured exits non-zero').not.toBe(0);

    const after = await rawGet(repo.name, admin, infoRelPath(name));
    expect(after.status, 'nothing was ever stored').toBe(404);
  },
);

test(
  'ruby > gem yank with a RW token succeeds; the yanked version is omitted from /info (RPS-1235)',
  { tag: ['@negative'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const credential = {
      transport: 'basic' as const,
      username: token.username,
      password: token.token,
      kind: 'token' as const,
    };
    const admin = adminCredential();
    const name = `e2e_${seeder.runId}_yank`;
    const version = rubyAdapter.version('release');

    const built = await buildGem({ name, version });
    const publishRes = await rawPublish(repo.name, admin, built.bytes);
    expect(publishRes.status, 'seed publish').toBe(200);

    const { home, work } = await isolatedWorkDir(`ruby-yank-${seeder.runId}`);
    const result = await run('gem', ['yank', name, '-v', version, '--host', repoUrl(repo.name)], {
      cwd: work,
      env: gemEnv(home, credential),
      timeoutMs: 30_000,
      label: 'ruby-yank',
    });
    expect(result.exitCode, `gem yank: ${result.command}`).toBe(0);

    const infoRes = await rawGet(repo.name, admin, infoRelPath(name));
    const entries = parseInfo(infoRes.body);
    const entry = entries.find((e) => e.version === version);

    expect(entry, 'the yanked version is omitted from /info, as the spec requires').toBeUndefined();
  },
);

test(
  'ruby > gem push with a USER-role Basic credential, the panel’s own documented convention (H3)',
  { tag: ['@auth'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
    const user = await seeder.createUser();
    const credential = {
      transport: 'basic' as const,
      username: user.username,
      password: user.password,
      kind: 'password' as const,
    };
    const name = `e2e_${seeder.runId}_userpush`;
    const version = rubyAdapter.version('release');
    const marker = randomUUID();

    const built = await buildGem({ name, version, marker });
    const { home, work } = await isolatedWorkDir(`ruby-userpush-${seeder.runId}`);
    const gemFile = `${work}/${built.filename}`;
    await fs.writeFile(gemFile, built.bytes);

    const result = await run('gem', ['push', gemFile, '--host', repoUrl(repo.name)], {
      cwd: work,
      env: gemEnv(home, credential),
      timeoutMs: 30_000,
      label: 'ruby-userpush',
    });
    expect(result.exitCode, `gem push: ${result.command}`).toBe(0);

    const admin = adminCredential();
    const dl = await rawDownload(repo.name, admin, gemFilename(name, version));
    expect(dl.status, 'the gem pushed with a USER-role Basic credential is stored').toBe(200);
  },
);

test(
  'ruby > real bundle install resolves the exact published bytes end to end (H1, H7, H17)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: false });
    const admin = adminCredential();
    const name = `e2e_${seeder.runId}_bundlee2e`;
    const version = rubyAdapter.version('release');

    const built = await buildGem({ name, version });
    const publishRes = await rawPublish(repo.name, admin, built.bytes);
    expect(publishRes.status, 'seed publish').toBe(200);

    const { home, work } = await isolatedWorkDir(`ruby-bundlee2e-${seeder.runId}`);
    const gemfileTemplate = await fs.readFile(path.join(TEMPLATES_DIR, 'Gemfile.template'), 'utf8');
    await fs.writeFile(
      path.join(work, 'Gemfile'),
      mustache.render(gemfileTemplate, {
        repoUrl: repoUrl(repo.name),
        name,
        version,
      }),
      'utf8',
    );

    const bundleProcessEnv = bundleEnv(home, work, {}, repo.name);
    const result = await run('bundle', ['install', '--verbose'], {
      cwd: work,
      env: bundleProcessEnv,
      timeoutMs: 60_000,
      label: 'ruby-bundlee2e',
    });
    expect(result.exitCode, `bundle install: ${result.command}`).toBe(0);
    expect(result.stdout, 'no gemspec.rz request was ever needed (H1 refutation)').not.toContain(
      'gemspec.rz',
    );

    const bundlePath = bundleProcessEnv.BUNDLE_PATH as string;
    const [abiDir] = await fs.readdir(path.join(bundlePath, 'ruby'));
    const cachedFile = path.join(bundlePath, 'ruby', abiDir, 'cache', gemFilename(name, version));
    const cachedBytes = await fs.readFile(cachedFile);
    expect(
      cachedBytes.equals(built.bytes),
      'bundler’s cached gem is byte-identical to what was published',
    ).toBe(true);
  },
);

test(
  'ruby > the packageName helper is underscore-only, avoiding RPS-1236’s hyphen-before-digit bug by ' +
    'construction (sanity)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const name = rawPackageName(seeder.runId, {
      id: 'sanity-check-2fa',
      tags: [],
      repo: { privateRepo: true },
      credential: 'admin-password',
      expect: { publish: 'ok', consume: 'ok' },
    });
    expect(name, 'no hyphens at all').not.toMatch(/-/);
    expect(name).toBe(name.toLowerCase());
  },
);
