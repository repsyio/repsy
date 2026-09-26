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
 * `npmFamilyAdapter(client)` (RPS-1330): turns any `NpmFamilyClient` into the `ProtocolAdapter` the
 * shared scenario loop (`scenarios/loop.ts`) runs the catalog against, so the 13 npm scenarios run
 * once per client. `protocol` stays `'npm'` (the same catalog rows, the same `REPO_TYPE`, the same
 * expectations as `clients/npm.ts`'s adapter); `label` and `tags` carry the client
 * (`npm[pnpm] > token-rw`, `@pnpm`).
 *
 * The contract is `clients/npm.ts`'s, client by client: `publish` packs the rendered package with the
 * client and publishes that exact tarball; its `Outcome` comes from a raw HTTP companion probe with
 * the SAME credential, re-PUTting a publish document built on the same bytes (a byte-identical
 * override of the version the client just created), never from the client's exit code, which is
 * asserted separately. `resolve` installs the version into an empty project and reads the marker back
 * out of the installed package; its `Outcome` comes from a raw packument GET. `fingerprint` and
 * `expectNothingStored` are `clients/npm.ts`'s own.
 *
 * A consume-only client (deno, RPS-1486) is `npmFamilyAdapter(npmClient, denoClient)`: `client` packs
 * and publishes (`publish`, `seedPublish`), `consumer` (default: `client`) resolves, and the title,
 * tag and the client named in a failure message are the consumer's.
 */
import type { AdapterResult, ProtocolAdapter } from '../../scenarios/adapter.js';
import { boundedSemverVersion, slugify } from '../../scenarios/coordinates.js';
import { outcomeForStatus } from '../../scenarios/types.js';
import type { SeedResult, World } from '../../scenarios/world.js';
import { expectNothingStored, fingerprint, MARKER_FILENAME, type NpmFingerprint } from '../npm.js';
import { buildPublishDocument, rawGetPackument, rawPublish, sha256Hex } from '../npm-raw.js';
import type { NpmFamilyClient, RegistryBinding } from './client.js';
import { publishPackage, renderConsumer } from './fixtures.js';

function bindingFor(world: World): RegistryBinding {
  return { repoName: world.repoName, credential: world.credential };
}

interface PublishRun {
  exitCode: number;
  command: string;
  tarballBytes: Buffer;
  marker: string;
}

export function npmFamilyAdapter(
  client: NpmFamilyClient,
  consumer: NpmFamilyClient = client,
): ProtocolAdapter<NpmFingerprint> {
  async function publishWithClient(world: World, label: string): Promise<PublishRun> {
    const ctx = await client.prepare(label, [bindingFor(world)]);
    const { packageName, version } = world.publishTarget;

    // `forceRepublish` for the `override`/`no-override` scenarios: the client would otherwise refuse
    // the redeploy on its own before any request is sent (clients/npm.ts).
    const published = await publishPackage(
      client,
      ctx,
      { packageName, version },
      { forceRepublish: world.scenario.reuseCoordinates === true },
    );

    return {
      exitCode: published.result.exitCode,
      command: published.result.command,
      tarballBytes: published.tarball.bytes,
      marker: published.marker,
    };
  }

  async function publish(world: World): Promise<AdapterResult> {
    const published = await publishWithClient(world, `publish-${world.scenario.id}`);

    const document = buildPublishDocument({
      repoName: world.repoName,
      packageName: world.publishTarget.packageName,
      version: world.publishTarget.version,
      tarballBytes: published.tarballBytes,
    });
    const rawRes = await rawPublish(
      world.repoName,
      world.credential,
      world.publishTarget.packageName,
      document,
    );

    return {
      outcome: outcomeForStatus(rawRes.status),
      httpStatus: rawRes.status,
      clientExitCode: published.exitCode,
      command: published.command,
      contentSha256: sha256Hex(published.marker),
    };
  }

  async function seedPublish(world: World): Promise<SeedResult> {
    const published = await publishWithClient(world, `seed-${world.scenario.id}`);
    if (published.exitCode !== 0) {
      throw new Error(
        `${client.label} adapter: pre-publish for scenario "${world.scenario.id}" failed ` +
          `unexpectedly (exit ${published.exitCode}); its "consume: ok" expectation depends on ` +
          'this package actually existing.',
      );
    }
    return { contentSha256: sha256Hex(published.marker) };
  }

  async function resolve(world: World): Promise<AdapterResult> {
    const ctx = await consumer.prepare(`consume-${world.scenario.id}`, [bindingFor(world)]);
    const { packageName, version } = world.consumeTarget;

    // A source-free consumer project, so the explicit `<pkg>@<version>` install target is what
    // resolves anything (clients/npm.ts).
    await renderConsumer(ctx.work, `e2e-consumer-${world.scenario.id}`);
    const install = await consumer.add(ctx, [`${packageName}@${version}`]);

    // The auth-only companion probe: a packument GET never touches the tarball path, so its status
    // is a clean signal of authn/authz alone.
    const rawRes = await rawGetPackument(world.repoName, world.credential, packageName);
    const marker = await consumer.readInstalledFile(ctx, packageName, MARKER_FILENAME);

    return {
      outcome: outcomeForStatus(rawRes.status),
      httpStatus: rawRes.status,
      clientExitCode: install.exitCode,
      command: install.command,
      contentSha256: marker === undefined ? undefined : sha256Hex(marker),
      resolvedFile:
        marker === undefined ? undefined : `node_modules/${packageName}/${MARKER_FILENAME}`,
    };
  }

  return {
    protocol: 'npm',
    label: consumer.label,
    tags: [consumer.tag],
    client: { name: consumer.label, publishVerb: 'publish', consumeVerb: 'install' },
    knownClientExitDisagreement: (scenario, side, outcome) =>
      (side === 'publish' ? client : consumer).exitQuirk?.(scenario, side, outcome),

    packageName: (runId, scenario) => `e2e-${runId}-${slugify(scenario.id)}`,
    version: () => boundedSemverVersion(),

    publish,
    resolve,
    seedPublish,

    fingerprint,
    expectNothingStored,
  };
}
