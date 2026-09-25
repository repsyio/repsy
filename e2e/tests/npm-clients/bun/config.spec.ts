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
 * How bun is configured for, and authenticates against, a Repsy npm repository (RPS-1330). `bunfig.toml`
 * has no read-back command, so what bun really takes from each config is asserted by what crosses the
 * wire (`wire-recorder.ts`) and by what the client does with it:
 *
 *  - `.npmrc` alone (H-11): `registry` + `_authToken` (Bearer) or `_auth` (Basic) read from
 *    `$HOME/.npmrc`, sent to the packument AND the tarball, with the abbreviated `Accept`.
 *  - `.npmrc` scopes: `@scope:registry` + path-scoped credentials keep each repository's token to that
 *    repository, as `[install.scopes]` does (`matrix/scoped-routing.spec.ts`).
 *  - `bun publish` sends only a Bearer TOKEN (probed): a bunfig `username`/`password` or an `.npmrc`
 *    `_auth` stops it at "missing authentication" before any request, while a password installs fine.
 *    A password becomes a token through the registry's couch login (what `bunClient.publish` does).
 *  - bun sends the registry's credential to whatever host `dist.tarball` names (H-16 refuted for bun 1.3.14:
 *    it does not withhold it from another origin). What keeps a credential at the registry is the
 *    registry always naming its own address (RPS-1333), not the client.
 *  - the network seal's success side (H-11 / plan (b)): bun answers through the registry host under the
 *    dead-proxy environment because of `NO_PROXY`, and only because of it.
 */
import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import {
  bunClient,
  bunExec,
  bunfigOnlyClient,
  bunNpmrcClient,
} from '../../../src/clients/npm-family/bun-client.js';
import type { RegistryBinding } from '../../../src/clients/npm-family/client.js';
import {
  adminBinding,
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import type { Seeder } from '../../../src/seed/seeder.js';

/** bun's own `Accept` for a packument on an install: the abbreviated document first (H-11). */
const BUN_INSTALL_ACCEPT =
  'application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8, */*';

/** Per credential kind: the consumer binding, and the `Authorization` scheme `.npmrc` sends it with. */
const NPMRC_CREDENTIAL = {
  token: {
    scheme: 'Bearer',
    binding: async (seeder: Seeder, repoName: string): Promise<RegistryBinding> =>
      tokenBinding(seeder, repoName, { readOnly: true }),
    whoami: (binding: RegistryBinding) => binding.credential.username,
  },
  password: {
    scheme: 'Basic',
    binding: async (_seeder: Seeder, repoName: string): Promise<RegistryBinding> =>
      adminBinding(repoName),
    whoami: () => env.adminUsername,
  },
};

for (const kind of ['token', 'password'] as const) {
  test(
    `bun reads the registry and a ${kind} from .npmrc alone`,
    {
      tag: ['@bun', '@config'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const name = packageNameFor(seeder, `npmrc-${kind}`);
      const published = await publishPackage(
        bunClient,
        await bunClient.prepare('npmrc-pub', [writer]),
        { packageName: name, version: '1.0.0' },
      );
      expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

      const recorder = await startWireRecorder({ rewriteTarballUrls: true });
      try {
        const { scheme, binding: bindingOf, whoami: whoamiOf } = NPMRC_CREDENTIAL[kind];
        const binding = await bindingOf(seeder, repo.name);
        const consumer = await bunNpmrcClient.prepare('npmrc-con', [
          { ...binding, baseUrl: recorder.baseUrl },
        ]);
        await renderConsumer(consumer.work, 'npmrc-consumer');
        const added = await bunNpmrcClient.add(consumer, [`${name}@1.0.0`]);
        expect(added.exitCode, `add: ${added.command}\n${added.stderr}`).toBe(0);
        expect(await bunNpmrcClient.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(
          published.marker,
        );

        const [packument, ...rest] = recorder.entries;
        expect(rest, 'one tarball GET after the packument GET').toHaveLength(1);
        expect(packument?.path).toBe(`/${repo.name}/${name}`);
        expect(packument?.authScheme, 'the packument GET carries the .npmrc credential').toBe(
          scheme,
        );
        expect(packument?.accept, 'the abbreviated packument, as with a bunfig').toBe(
          BUN_INSTALL_ACCEPT,
        );
        expect(rest[0]?.path).toBe(`/${repo.name}/${name}/-/${name}-1.0.0.tgz`);
        expect(rest[0]?.authScheme, 'and so does the tarball GET, on the same host').toBe(scheme);

        // The same .npmrc drives `whoami` (it needs credentials, RPS-1329).
        const whoami = await bunNpmrcClient.whoami?.(consumer);
        expect(whoami?.exitCode, `whoami: ${whoami?.command}\n${whoami?.stderr}`).toBe(0);
        expect(whoami?.stdout.trim()).toBe(whoamiOf(binding));
      } finally {
        await recorder.stop();
      }
    },
  );
}

test(
  "bun keeps each repository's token to that repository with .npmrc scopes",
  {
    tag: ['@bun', '@config', '@scopes'],
  },
  async ({ seeder }) => {
    const repoA = await newRepo(seeder);
    const repoB = await newRepo(seeder);
    const scope = `@e2e-${seeder.runId}`;
    const scoped = packageNameFor(seeder, 'in-a', true);
    const unscoped = packageNameFor(seeder, 'in-b');

    const publishedA = await publishPackage(
      bunClient,
      await bunClient.prepare('npmrc-scopes-pub-a', [
        { ...(await tokenBinding(seeder, repoA.name, { readOnly: false })), scope },
      ]),
      { packageName: scoped, version: '1.0.0' },
    );
    const publishedB = await publishPackage(
      bunClient,
      await bunClient.prepare('npmrc-scopes-pub-b', [
        await tokenBinding(seeder, repoB.name, { readOnly: false }),
      ]),
      { packageName: unscoped, version: '1.0.0' },
    );
    expect(publishedA.result.exitCode).toBe(0);
    expect(publishedB.result.exitCode).toBe(0);

    const recorder = await startWireRecorder({ rewriteTarballUrls: true });
    try {
      const readerA = await tokenBinding(seeder, repoA.name, { readOnly: true });
      const readerB = await tokenBinding(seeder, repoB.name, { readOnly: true });
      const consumer = await bunNpmrcClient.prepare('npmrc-scopes-con', [
        { ...readerA, scope, baseUrl: recorder.baseUrl },
        { ...readerB, baseUrl: recorder.baseUrl },
      ]);
      await renderConsumer(consumer.work, 'npmrc-scopes-consumer');
      const added = await bunNpmrcClient.add(consumer, [`${scoped}@1.0.0`, `${unscoped}@1.0.0`]);
      expect(added.exitCode, `add both: ${added.command}\n${added.stderr}`).toBe(0);
      expect(await bunNpmrcClient.readInstalledFile(consumer, scoped, MARKER_FILENAME)).toBe(
        publishedA.marker,
      );
      expect(await bunNpmrcClient.readInstalledFile(consumer, unscoped, MARKER_FILENAME)).toBe(
        publishedB.marker,
      );

      const toA = recorder.under(`/${repoA.name}/`);
      const toB = recorder.under(`/${repoB.name}/`);
      expect(toA.length, 'packument and tarball of A').toBeGreaterThanOrEqual(2);
      expect(toB.length, 'packument and tarball of B').toBeGreaterThanOrEqual(2);
      expect(
        toA.map((entry) => entry.authorization),
        "every request to A carries A's token",
      ).toEqual(toA.map(() => `Bearer ${readerA.credential.password}`));
      expect(
        toB.map((entry) => entry.authorization),
        "every request to B carries B's token",
      ).toEqual(toB.map(() => `Bearer ${readerB.credential.password}`));
      expect(recorder.entries).toHaveLength(toA.length + toB.length);
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'bun publish sends only a Bearer token: a password reaches it as a login token',
  {
    tag: ['@bun', '@config', '@publish-auth'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const recorder = await startWireRecorder();
    try {
      const password = { ...adminBinding(repo.name), baseUrl: recorder.baseUrl };
      /** The requests since the `from`-th, as `<method> <scheme> <status>`. */
      const wire = (from: number) =>
        recorder.entries
          .slice(from)
          .map((entry) => `${entry.method} ${entry.authScheme} ${entry.status}`);

      // A bunfig `username`/`password` and an `.npmrc` `_auth` are what installs use, but
      // `bun publish` refuses both before sending anything ("missing authentication").
      for (const [config, client] of [
        ['bunfig username/password', bunfigOnlyClient],
        ['.npmrc _auth', bunNpmrcClient],
      ] as const) {
        const ctx = await client.prepare('publish-pw', [password]);
        const refused = await publishPackage(client, ctx, {
          packageName: packageNameFor(seeder, 'refused'),
          version: '1.0.0',
        });
        expect(refused.result.exitCode, `${config}: ${refused.result.command}`).not.toBe(0);
        expect(refused.result.stderr, config).toContain('missing authentication');
      }
      expect(recorder.entries, 'bun did not send a single request with a password').toHaveLength(0);

      // The same password used as the Bearer token itself is sent, and refused: only a deploy token
      // or the JWT of a login is one.
      const asToken = await bunNpmrcClient.prepare('publish-pw-bearer', [
        {
          ...password,
          credential: { ...password.credential, kind: 'token' },
        },
      ]);
      const bearer = await publishPackage(bunNpmrcClient, asToken, {
        packageName: packageNameFor(seeder, 'bearer'),
        version: '1.0.0',
      });
      expect(bearer.result.exitCode, `password as _authToken: ${bearer.result.command}`).not.toBe(
        0,
      );
      expect(bearer.result.stderr).toContain('unable to authenticate');
      expect(wire(0)).toEqual(['PUT Bearer 401']);
      const afterBearer = recorder.entries.length;

      // The login token: `bunClient.publish` trades the password for the registry's JWT.
      const name = packageNameFor(seeder, 'login');
      const published = await publishPackage(
        bunClient,
        await bunClient.prepare('publish-login', [password]),
        {
          packageName: name,
          version: '1.0.0',
        },
      );
      expect(
        published.result.exitCode,
        `publish: ${published.result.command}\n${published.result.stderr}`,
      ).toBe(0);
      expect(wire(afterBearer)).toEqual(['PUT Bearer 200']);

      // ... and the same password installs with Basic.
      const afterPublish = recorder.entries.length;
      const consumer = await bunClient.prepare('publish-login-con', [password]);
      await renderConsumer(consumer.work, 'login-consumer');
      const added = await bunClient.add(consumer, [`${name}@1.0.0`]);
      expect(added.exitCode, `add: ${added.command}\n${added.stderr}`).toBe(0);
      expect(recorder.entries[afterPublish]?.authScheme, 'a password installs as Basic').toBe(
        'Basic',
      );
      expect(await bunClient.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(
        published.marker,
      );
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'bun sends the registry credential to a tarball on another origin',
  {
    tag: ['@bun', '@config', '@tarball-host'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const name = packageNameFor(seeder, 'origin');
    const published = await publishPackage(
      bunClient,
      await bunClient.prepare('origin-pub', [writer]),
      { packageName: name, version: '1.0.0' },
    );
    expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

    // The registry is configured as the recorder (127.0.0.1:<port>), but the packument names the
    // registry's own address (RPS-1333) for the tarball: another host and port, on a private repo.
    const recorder = await startWireRecorder();
    try {
      const reader = await tokenBinding(seeder, repo.name, { readOnly: true });
      const consumer = await bunClient.prepare('origin-con', [
        { ...reader, baseUrl: recorder.baseUrl },
      ]);
      await renderConsumer(consumer.work, 'origin-consumer');
      const added = await bunExec(consumer, 'bun-add-verbose', [
        'add',
        `${name}@1.0.0`,
        '--no-save',
        '--verbose',
      ]);
      expect(added.exitCode, `add: ${added.command}\n${added.stderr}`).toBe(0);
      expect(await bunClient.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(
        published.marker,
      );

      const tarballUrl = `${env.repoBaseUrl}/${repo.name}/${name}/-/${name}-1.0.0.tgz`;
      expect(tarballUrl.startsWith(recorder.baseUrl), 'a different origin').toBe(false);
      expect(recorder.entries.map((entry) => entry.path)).toEqual([`/${repo.name}/${name}`]);

      // `--verbose` prints each request bun makes with its headers: the tarball's carries the token.
      const lines = added.stderr.split('\n');
      const start = lines.findIndex((line) => line.includes(`GET ${tarballUrl}`));
      expect(start, `bun requested ${tarballUrl}`).toBeGreaterThanOrEqual(0);
      // The request's header lines are the run of lines starting with `>` after its first line.
      const request = lines
        .slice(start + 1)
        .join('\n')
        .split(/\n(?!>)/)[0]
        ?.split('\n');
      expect(request, 'the tarball request carries the registry credential').toContain(
        '> Authorization: Bearer ***',
      );
    } finally {
      await recorder.stop();
    }
  },
);

test(
  'bun reaches the registry through NO_PROXY under the sealed network, and only through it',
  {
    tag: ['@bun', '@config', '@sealed'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await bunClient.prepare('seal', [writer]);
    const name = packageNameFor(seeder, 'sealed');
    const published = await publishPackage(bunClient, ctx, { packageName: name, version: '1.0.0' });
    expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

    // HTTP_PROXY / HTTPS_PROXY are the dead port; NO_PROXY names the registry's host.
    expect(ctx.env.HTTP_PROXY).toBe('http://127.0.0.1:9');
    expect(ctx.env.NO_PROXY).toContain(new URL(env.repoBaseUrl).hostname);
    const consumer = await bunClient.prepare('seal-con', [writer]);
    await renderConsumer(consumer.work, 'seal-consumer');
    const added = await bunClient.add(consumer, [`${name}@1.0.0`]);
    expect(added.exitCode, `add under the seal: ${added.command}\n${added.stderr}`).toBe(0);
    const whoami = await bunClient.whoami?.(consumer);
    expect(whoami?.exitCode, `whoami under the seal: ${whoami?.stderr}`).toBe(0);

    // Without the exemption, the very same registry is unreachable: bun does use the proxy variables.
    // (A fresh cache: the consumer above already has this package's manifest and tarball.)
    const fresh = await bunClient.prepare('seal-fresh', [writer]);
    await renderConsumer(fresh.work, 'seal-fresh-consumer');
    const unsealed = { ...fresh, env: { ...fresh.env, NO_PROXY: '', no_proxy: '' } };
    const refused = await bunClient.add(unsealed, [`${name}@1.0.0`]);
    expect(refused.exitCode, 'no NO_PROXY: the dead proxy is used').not.toBe(0);
    expect(`${refused.stdout}\n${refused.stderr}`).toContain('ConnectionRefused');
  },
);

test(
  'bun installs an os=win32 optional dependency on linux because the packument it reads is abbreviated',
  {
    tag: ['@bun', '@config', '@abbreviated'],
  },
  async ({ seeder }) => {
    const repo = await newRepo(seeder);
    const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
    const ctx = await bunClient.prepare('optional-pub', [writer]);
    const platform = packageNameFor(seeder, 'winonly');
    const app = packageNameFor(seeder, 'app');
    for (const spec of [
      { packageName: platform, version: '1.0.0', manifest: { os: ['win32'], cpu: ['arm64'] } },
      { packageName: app, version: '1.0.0', optionalDependencies: { [platform]: '1.0.0' } },
    ]) {
      const published = await publishPackage(bunClient, ctx, spec);
      expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);
    }

    // The same install twice: as bun asks (the abbreviated packument, which lacks `os`/`cpu`,
    // RPS-1356), and with the request rewritten to the full packument, which has them.
    const installs = {} as Record<'abbreviated' | 'full', boolean>;
    for (const [accepted, forwardHeaders] of [
      ['abbreviated', undefined],
      ['full', { accept: 'application/json' }],
    ] as const) {
      const recorder = await startWireRecorder({ forwardHeaders });
      try {
        const reader = await tokenBinding(seeder, repo.name, {
          readOnly: true,
          baseUrl: recorder.baseUrl,
        });
        const consumer = await bunClient.prepare(`optional-${accepted}`, [reader]);
        await renderConsumer(consumer.work, 'optional-consumer');
        const added = await bunClient.add(consumer, [`${app}@1.0.0`]);
        expect(added.exitCode, `add (${accepted}): ${added.command}\n${added.stderr}`).toBe(0);
        expect(await bunClient.readInstalledFile(consumer, app, MARKER_FILENAME)).toBeDefined();
        expect(recorder.entries[0]?.accept, 'bun asked for the abbreviated packument').toBe(
          BUN_INSTALL_ACCEPT,
        );
        installs[accepted] =
          (await bunClient.readInstalledFile(consumer, platform, MARKER_FILENAME)) !== undefined;
      } finally {
        await recorder.stop();
      }
    }
    expect(installs.abbreviated, 'RPS-1356: installed from the abbreviated packument').toBe(true);
    expect(installs.full, 'skipped when the packument carries os/cpu').toBe(false);
  },
);
