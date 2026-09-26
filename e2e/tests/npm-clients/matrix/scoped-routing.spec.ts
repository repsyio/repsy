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
 * Matrix row 16 (RPS-1330): two repositories, two credentials, per client. Scope `@<run>` resolves
 * from repository A with A's deploy token, everything unscoped from repository B with B's. The
 * consumer's client is pointed at a wire recorder (`wire-recorder.ts`, tarball URLs rewritten so the
 * tarball GETs go through it too), which proves the property that matters -- a credential is only ever
 * sent to the repository it belongs to -- from what actually crossed the wire, not from the config:
 * every request under `/<A>/` carries token A and none carries token B, and vice versa.
 */
import { MARKER_FILENAME } from '../../../src/clients/npm.js';
import type { RegistryBinding } from '../../../src/clients/npm-family/client.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { clientsWith } from '../../../src/clients/npm-family/registry.js';
import { startWireRecorder } from '../../../src/clients/npm-family/wire-recorder.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { optedIn } from '../../../src/stack-overlays.js';
import { repoPath } from '../../../src/repo-url.js';

for (const client of clientsWith('publish')) {
  test(
    `${client.label} sends each repository's token to that repository only`,
    {
      tag: [client.tag, '@scopes'],
    },
    async ({ seeder }) => {
      test.fail(
        optedIn('tls'),
        'RPS-1559: the SSL connectors miss EncodedSolidusHandling.DECODE (encoded slash -> bodyless 400)',
      );
      const repoA = await newRepo(seeder);
      const repoB = await newRepo(seeder);
      const scope = `@e2e-${seeder.runId}`;
      const scoped = packageNameFor(seeder, 'in-a', true);
      const unscoped = packageNameFor(seeder, 'in-b');

      const writerA = await tokenBinding(seeder, repoA.name, { readOnly: false });
      const writerB = await tokenBinding(seeder, repoB.name, { readOnly: false });
      const publishedA = await publishPackage(
        client,
        await client.prepare('scopes-pub-a', [{ ...writerA, scope }]),
        { packageName: scoped, version: '1.0.0' },
      );
      const publishedB = await publishPackage(
        client,
        await client.prepare('scopes-pub-b', [writerB]),
        { packageName: unscoped, version: '1.0.0' },
      );
      expect(
        publishedA.result.exitCode,
        `publish ${scoped} to A: ${publishedA.result.command}`,
      ).toBe(0);
      expect(
        publishedB.result.exitCode,
        `publish ${unscoped} to B: ${publishedB.result.command}`,
      ).toBe(0);

      const recorder = await startWireRecorder({ rewriteTarballUrls: true });
      try {
        const readerA = await tokenBinding(seeder, repoA.name, { readOnly: true });
        const readerB = await tokenBinding(seeder, repoB.name, { readOnly: true });
        const bindings: RegistryBinding[] = [
          { ...readerA, scope, baseUrl: recorder.baseUrl },
          { ...readerB, baseUrl: recorder.baseUrl },
        ];
        const consumer = await client.prepare('scopes-con', bindings);
        await renderConsumer(consumer.work, 'scopes-consumer');
        const added = await client.add(consumer, [`${scoped}@1.0.0`, `${unscoped}@1.0.0`]);
        expect(added.exitCode, `add both: ${added.command}\n${added.stderr}`).toBe(0);

        expect(await client.readInstalledFile(consumer, scoped, MARKER_FILENAME)).toBe(
          publishedA.marker,
        );
        expect(await client.readInstalledFile(consumer, unscoped, MARKER_FILENAME)).toBe(
          publishedB.marker,
        );

        const tokenA = `Bearer ${readerA.credential.password}`;
        const tokenB = `Bearer ${readerB.credential.password}`;
        const toA = recorder.under(`/${repoPath(repoA.name)}/`);
        const toB = recorder.under(`/${repoPath(repoB.name)}/`);
        expect(
          toA.length,
          'requests reached repository A (packument and tarball)',
        ).toBeGreaterThanOrEqual(2);
        expect(
          toB.length,
          'requests reached repository B (packument and tarball)',
        ).toBeGreaterThanOrEqual(2);
        expect(
          toA.some((entry) => entry.path.includes('/-/')),
          "A's tarball was fetched through the recorder too",
        ).toBe(true);
        expect(
          toB.some((entry) => entry.path.includes('/-/')),
          "B's tarball was fetched through the recorder too",
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
      } finally {
        await recorder.stop();
      }
    },
  );
}
