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
 * How Deno authenticates against a Repsy npm repository and what it asks for on the wire (RPS-1486),
 * asserted from what `wire-recorder.ts` saw between Deno and the registry (tarball URLs rewritten, so the
 * tarball GETs cross it too). Deno reads `$HOME/.npmrc` (`deno-client.ts`), so the credential kinds are
 * `npm`'s and the cells below are the catalog's `deploy token: Bearer or Basic` question (H25):
 *
 *  - a deploy token as `_authToken` goes out as `Bearer`, on the packument AND the tarball GET;
 *  - the same token as `_auth` (`<username>:<token>`, what a Basic-only tool sends) goes out as `Basic`
 *    and the registry accepts it as well;
 *  - a password (`_auth`) is `Basic`;
 *  - with the `minimumDependencyAge` gate off Deno asks for the ABBREVIATED packument (H24), the same
 *    `Accept` bun and yarn classic send, and the registry serves it (a 200 the install goes on with);
 *  - `DENO_AUTH_TOKENS` does not authenticate to an npm registry: Deno sends it with its first packument
 *    request only, the next ones carry no `Authorization` and are refused. It is Deno's behaviour, not the
 *    registry's (the first request, with the token, is a 200), pinned so a Deno that fixes it shows up;
 *  - two repositories, two credentials: a credential goes only to the repository it belongs to.
 */
import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import type { RegistryBinding } from '../../../src/clients/npm-family/client.js';
import { denoClient, denoExec, NO_AGE_GATE } from '../../../src/clients/npm-family/deno-client.js';
import {
  adminBinding,
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { npmClient } from '../../../src/clients/npm-family/npm-client.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { env } from '../../../src/env.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import type { Seeder } from '../../../src/seed/seeder.js';

/** Deno's own `Accept` for a packument on an install: the abbreviated document first (H24). */
const DENO_INSTALL_ACCEPT =
  'application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8, */*';

/** What the registry saw as the client: `Deno/<pinned version>`. */
const DENO_USER_AGENT = new RegExp(
  `^Deno/${(process.env.NPM_CLIENTS_DENO_VERSION ?? '').replaceAll('.', '\\.')}$`,
);

/** Publishes one package with npm into a fresh private repository. */
async function publishOne(seeder: Seeder, label: string) {
  const repo = await newRepo(seeder);
  const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
  const name = packageNameFor(seeder, label);
  const published = await publishPackage(npmClient, await npmClient.prepare('deno-pub', [writer]), {
    packageName: name,
    version: '1.0.0',
  });
  expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);
  return { repoName: repo.name, name, marker: published.marker };
}

const KINDS = {
  'a deploy token as _authToken (Bearer)': {
    scheme: 'Bearer',
    binding: async (
      seeder: Seeder,
      repoName: string,
      baseUrl: string,
    ): Promise<RegistryBinding> => ({
      ...(await tokenBinding(seeder, repoName, { readOnly: true })),
      baseUrl,
    }),
  },
  'a deploy token as _auth user:token (Basic)': {
    scheme: 'Basic',
    binding: async (
      seeder: Seeder,
      repoName: string,
      baseUrl: string,
    ): Promise<RegistryBinding> => {
      const token = await tokenBinding(seeder, repoName, { readOnly: true });
      // A password-kind credential is what makes `writeNpmrc` render `_auth`.
      return { ...token, baseUrl, credential: { ...token.credential, kind: 'password' } };
    },
  },
  'the admin password as _auth (Basic)': {
    scheme: 'Basic',
    binding: async (_seeder: Seeder, repoName: string, baseUrl: string): Promise<RegistryBinding> =>
      adminBinding(repoName, { baseUrl }),
  },
} as const;

test.describe('deno credentials and the wire', () => {
  for (const [kind, { scheme, binding }] of Object.entries(KINDS)) {
    test(
      `deno reads the packument and tarball with ${kind}`,
      { tag: [denoClient.tag, '@wire'] },
      async ({ seeder }) => {
        const { repoName, name, marker } = await publishOne(seeder, 'traced');
        const recorder = await startWireRecorder({ rewriteTarballUrls: true });
        try {
          const consumer = await denoClient.prepare('wire-con', [
            await binding(seeder, repoName, recorder.baseUrl),
          ]);
          await renderConsumer(consumer.work, 'wire-consumer');
          const added = await denoClient.add(consumer, [`${name}@1.0.0`]);
          expect(added.exitCode, `add: ${added.command}\n${added.stderr}`).toBe(0);
          expect(await denoClient.readInstalledFile(consumer, name, MARKER_FILENAME)).toBe(marker);

          const packuments = recorder.entries.filter(
            (entry) => entry.method === 'GET' && entry.path === `/${repoName}/${name}`,
          );
          const tarballs = recorder.entries.filter(
            (entry) =>
              entry.method === 'GET' && entry.path === `/${repoName}/${name}/-/${name}-1.0.0.tgz`,
          );
          expect(packuments.length, 'the packument was asked for').toBeGreaterThanOrEqual(1);
          expect(tarballs, 'one tarball GET, through the recorder').toHaveLength(1);

          for (const entry of [...packuments, ...tarballs]) {
            expect(entry.authScheme, `${entry.path} carries the credential`).toBe(scheme);
            expect(entry.status, entry.path).toBe(200);
            expect(entry.userAgent).toMatch(DENO_USER_AGENT);
            expect(entry.accept, 'the abbreviated packument first (H24)').toBe(DENO_INSTALL_ACCEPT);
            expect(entry.ifNoneMatch, 'a first fetch is unconditional').toBeUndefined();
          }
          // The abbreviated document is served as such (Vary: Accept, RPS-1359).
          expect(packuments[0]?.responseVary ?? '').toMatch(/accept\b/i);
        } finally {
          await recorder.stop();
        }
      },
    );
  }

  test(
    'DENO_AUTH_TOKENS does not authenticate to an npm registry: the token is sent once, then refused requests carry none',
    { tag: [denoClient.tag, '@wire'] },
    async ({ seeder }) => {
      const { repoName, name } = await publishOne(seeder, 'auth-tokens');
      const reader = await tokenBinding(seeder, repoName, { readOnly: true });
      const recorder = await startWireRecorder({ rewriteTarballUrls: true });
      try {
        // No credential in `.npmrc` at all: only the registry address, and the token in Deno's own variable.
        const consumer = await denoClient.prepare('auth-tokens', [
          {
            repoName,
            credential: {},
            baseUrl: recorder.baseUrl,
          },
        ]);
        await renderConsumer(consumer.work, 'auth-tokens-consumer');
        const host = new URL(recorder.baseUrl).host;
        const result = await denoExec(
          {
            ...consumer,
            env: { ...consumer.env, DENO_AUTH_TOKENS: `${reader.credential.password}@${host}` },
            secrets: [...consumer.secrets, reader.credential.password ?? ''],
          },
          'deno-install-auth-tokens',
          ['install', NO_AGE_GATE, `npm:${name}@1.0.0`],
        );
        expect(
          result.exitCode,
          `Deno cannot install with DENO_AUTH_TOKENS alone: ${result.command}`,
        ).not.toBe(0);
        expect(`${result.stdout}\n${result.stderr}`).toContain('401');

        const packuments = recorder.entries.filter(
          (entry) => entry.path === `/${repoName}/${name}`,
        );
        expect(
          packuments.some((entry) => entry.authScheme === 'Bearer' && entry.status === 200),
          'the first packument request carries the token, and the registry accepts it',
        ).toBe(true);
        expect(
          packuments.some((entry) => entry.authorization === undefined && entry.status === 401),
          'a later one carries none, and is refused',
        ).toBe(true);
        expect(
          recorder.entries.some((entry) => entry.path.includes('/-/')),
          'no tarball was fetched',
        ).toBe(false);
      } finally {
        await recorder.stop();
      }
    },
  );
});

test.describe('deno scoped routing', () => {
  test(
    "deno sends each repository's token to that repository only",
    { tag: [denoClient.tag, '@scopes'] },
    async ({ seeder }) => {
      const repoA = await newRepo(seeder);
      const repoB = await newRepo(seeder);
      const scope = `@e2e-${seeder.runId}`;
      const scoped = packageNameFor(seeder, 'in-a', true);
      const unscoped = packageNameFor(seeder, 'in-b');

      const writerA = await tokenBinding(seeder, repoA.name, { readOnly: false });
      const writerB = await tokenBinding(seeder, repoB.name, { readOnly: false });
      const publishedA = await publishPackage(
        npmClient,
        await npmClient.prepare('scopes-pub-a', [{ ...writerA, scope }]),
        { packageName: scoped, version: '1.0.0' },
      );
      const publishedB = await publishPackage(
        npmClient,
        await npmClient.prepare('scopes-pub-b', [writerB]),
        { packageName: unscoped, version: '1.0.0' },
      );
      expect(publishedA.result.exitCode, `publish ${scoped}: ${publishedA.result.command}`).toBe(0);
      expect(publishedB.result.exitCode, `publish ${unscoped}: ${publishedB.result.command}`).toBe(
        0,
      );

      const recorder = await startWireRecorder({ rewriteTarballUrls: true });
      try {
        const readerA = await tokenBinding(seeder, repoA.name, { readOnly: true });
        const readerB = await tokenBinding(seeder, repoB.name, { readOnly: true });
        const consumer = await denoClient.prepare('scopes-con', [
          { ...readerA, scope, baseUrl: recorder.baseUrl },
          { ...readerB, baseUrl: recorder.baseUrl },
        ]);
        await renderConsumer(consumer.work, 'scopes-consumer');
        const added = await denoClient.add(consumer, [`${scoped}@1.0.0`, `${unscoped}@1.0.0`]);
        expect(added.exitCode, `add both: ${added.command}\n${added.stderr}`).toBe(0);

        expect(await denoClient.readInstalledFile(consumer, scoped, MARKER_FILENAME)).toBe(
          publishedA.marker,
        );
        expect(await denoClient.readInstalledFile(consumer, unscoped, MARKER_FILENAME)).toBe(
          publishedB.marker,
        );

        const tokenA = `Bearer ${readerA.credential.password}`;
        const tokenB = `Bearer ${readerB.credential.password}`;
        const toA = recorder.under(`/${repoA.name}/`);
        const toB = recorder.under(`/${repoB.name}/`);
        expect(
          toA.some((entry) => entry.path.includes('/-/')),
          "A's tarball crossed the recorder",
        ).toBe(true);
        expect(
          toB.some((entry) => entry.path.includes('/-/')),
          "B's tarball crossed the recorder",
        ).toBe(true);
        expect(
          toA.every((entry) => entry.status === 200),
          'every request to A succeeded',
        ).toBe(true);
        expect(
          toB.every((entry) => entry.status === 200),
          'every request to B succeeded',
        ).toBe(true);
        expect(
          toA.map((entry) => entry.authorization),
          "every request to A carries A's token",
        ).toEqual(toA.map(() => tokenA));
        expect(
          toB.map((entry) => entry.authorization),
          "every request to B carries B's token",
        ).toEqual(toB.map(() => tokenB));
        expect(recorder.entries, 'nothing went anywhere else through the recorder').toHaveLength(
          toA.length + toB.length,
        );
        expect(env.repoBaseUrl).toBeTruthy();
      } finally {
        await recorder.stop();
      }
    },
  );
});
