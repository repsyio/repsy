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
 * Matrix row 7 (RPS-1330): deprecation, per client with a deprecate command (`deprecateCmd`) as the
 * publisher, and then EVERY client with `frozenInstall` as the consumer (a deprecation is only useful
 * if the installing client surfaces it). The registry keeps the message on the version in both the
 * full and the abbreviated packument, the installing client prints it, and deprecating again with an
 * empty message clears it.
 */
import { rawGetPackument } from '../../../src/clients/npm-raw.js';
import type { ClientId } from '../../../src/clients/npm-family/client.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  renderConsumer,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { clientsWith } from '../../../src/clients/npm-family/registry.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';

/**
 * Whether a client prints the deprecation message when it installs the version. Berry does not: the
 * registry serves the message (`yarn npm info -f deprecated` shows it, `tests/npm-clients/yarn-berry`),
 * but neither `yarn add` nor `yarn install` says a word about it.
 */
const PRINTS_DEPRECATION_ON_INSTALL: Partial<Record<ClientId, boolean>> = {
  npm: true,
  'yarn-berry': false,
};

async function deprecatedOf(
  repoName: string,
  packageName: string,
  version: string,
  abbreviated: boolean,
): Promise<string | undefined> {
  const res = await rawGetPackument(repoName, adminCredential(), packageName, abbreviated);
  expect(res.status, `GET packument (${abbreviated ? 'abbreviated' : 'full'})`).toBe(200);
  const doc = JSON.parse(res.body.toString('utf8')) as {
    versions: Record<string, { deprecated?: string }>;
  };
  return doc.versions[version]?.deprecated;
}

for (const publisher of clientsWith('deprecateCmd')) {
  test.describe(`${publisher.label} deprecates a version`, () => {
    test(
      `${publisher.label} deprecate is served, surfaced by every client, and cleared`,
      {
        tag: [publisher.tag, '@deprecate'],
      },
      async ({ seeder }) => {
        const repo = await newRepo(seeder);
        const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
        const ctx = await publisher.prepare('deprecate', [writer]);
        const name = packageNameFor(seeder, 'old');
        const message = 'use the newer release instead';

        for (const version of ['1.0.0', '1.1.0']) {
          const published = await publishPackage(publisher, ctx, { packageName: name, version });
          expect(published.result.exitCode, `publish ${version}: ${published.result.command}`).toBe(
            0,
          );
        }

        const deprecated = await publisher.deprecate?.(ctx, `${name}@1.0.0`, message);
        expect(
          deprecated?.exitCode,
          `deprecate: ${deprecated?.command}\n${deprecated?.stderr}`,
        ).toBe(0);

        expect(await deprecatedOf(repo.name, name, '1.0.0', false), 'full packument').toBe(message);
        expect(await deprecatedOf(repo.name, name, '1.0.0', true), 'abbreviated packument').toBe(
          message,
        );
        expect(
          await deprecatedOf(repo.name, name, '1.1.0', false),
          'the other version is untouched',
        ).toBeUndefined();

        for (const consumerClient of clientsWith('frozenInstall')) {
          const consumer = await consumerClient.prepare(`deprecate-${consumerClient.id}`, [
            await tokenBinding(seeder, repo.name, { readOnly: true }),
          ]);
          await renderConsumer(consumer.work, 'deprecate-consumer');
          const added = await consumerClient.add(consumer, [`${name}@1.0.0`]);
          expect(
            added.exitCode,
            `${consumerClient.label} add: ${added.command}\n${added.stderr}`,
          ).toBe(0);
          expect(
            `${added.stdout}\n${added.stderr}`.includes(message),
            `${consumerClient.label} ${PRINTS_DEPRECATION_ON_INSTALL[consumerClient.id] ? 'prints' : 'does not print'} the deprecation message`,
          ).toBe(PRINTS_DEPRECATION_ON_INSTALL[consumerClient.id]);
        }

        const cleared = await publisher.deprecate?.(ctx, `${name}@1.0.0`, '');
        expect(cleared?.exitCode, `undeprecate: ${cleared?.command}\n${cleared?.stderr}`).toBe(0);
        expect(
          [undefined, ''],
          'an empty deprecation message clears the deprecation (the field is left empty or removed)',
        ).toContain(await deprecatedOf(repo.name, name, '1.0.0', false));
      },
    );
  });
}
