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
 * The scenario-loop adapter of the daemonless registry clients that COPY an image between an OCI
 * layout directory and a registry (`skopeo copy`, `regctl image copy`; RPS-1478 part B), the
 * counterpart of `docker.ts`'s `crane push`/`crane pull` one. Everything that does not depend on which
 * client moved the bytes is `docker.ts`'s own and reused as it is: the raw companion probes that
 * derive the `Outcome` (a byte-identical re-PUT of the pushed manifest after a publish, a manifest GET
 * after a consume, see `docker.ts`'s header), the tag-scoped `fingerprint`/`expectNothingStored`, the
 * post-round-trip checks and the coordinates. A `CopyClient` supplies only what is client-specific:
 * its environment, how it is given the credential (a rendered file, never argv), the two command
 * lines and how the pulled image is read back.
 *
 * The published image is the same hand-assembled OCI layout `crane push` sends (`docker-image.ts`),
 * pushed with the layout's own manifest digest pinned (`<dir>@sha256:...`, `skopeo`'s
 * `--preserve-digests`), so "the consumer got the very image" is the same digest comparison as for
 * `crane`: a client that recompressed a layer or converted the manifest would change it and fail.
 */
import { randomUUID } from 'node:crypto';
import path from 'node:path';

import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { MaterializedCredential, SeedResult, World } from '../scenarios/world.js';
import { buildImage } from './docker-image.js';
import { dockerAdapter, type DockerFingerprint, stripSha256Prefix } from './docker.js';
import { imageRef, rawGetManifest, rawPutManifest } from './docker-raw.js';
import { isolatedWorkDir, run, type RunResult } from './exec.js';

const PUBLISH_TIMEOUT_MS = 120_000;
const CONSUME_TIMEOUT_MS = 120_000;

export interface CopyClient {
  readonly name: 'skopeo' | 'regctl';
  readonly publishVerb: string;
  readonly consumeVerb: string;
  /** The client's whole environment: private `HOME`, the path of its credential file, nothing else. */
  env(home: string): NodeJS.ProcessEnv;
  /** Writes the credential (and the TLS setting) the way the client reads it: a file, never argv. */
  renderAuth(home: string, credential: MaterializedCredential): Promise<void>;
  /** The copy of a built layout's image into the registry. */
  pushArgs(built: { dir: string; manifestDigest: string }, ref: string): string[];
  /** The copy of the registry's image into a fresh directory. */
  pullArgs(ref: string, destDir: string): string[];
  /** The manifest digest (bare hex) of the pulled image in `destDir` and the file that proves it. */
  readPulled(destDir: string): Promise<{ hex: string; file: string } | undefined>;
}

/** The secrets `run()` must redact from a command line and its output. */
export function secretsOf(credential: MaterializedCredential): string[] {
  return [credential.password].filter((s): s is string => Boolean(s));
}

interface PublishRun {
  exitCode: number;
  command: string;
  manifestBytes: Buffer;
  manifestDigest: string;
  manifestMediaType: string;
}

export function copyAdapter(client: CopyClient): ProtocolAdapter<DockerFingerprint> {
  async function publishWithClient(world: World, label: string): Promise<PublishRun> {
    const { home, work } = await isolatedWorkDir(label);
    const { packageName: image, version: tag } = world.publishTarget;
    const built = await buildImage({ dir: path.join(work, 'image'), marker: randomUUID() });
    await client.renderAuth(home, world.credential);

    const result = await run(
      client.name,
      client.pushArgs(built, imageRef(world.repoName, image, tag)),
      {
        cwd: work,
        env: client.env(home),
        timeoutMs: PUBLISH_TIMEOUT_MS,
        redact: secretsOf(world.credential),
        label,
      },
    );
    return {
      exitCode: result.exitCode,
      command: result.command,
      manifestBytes: built.manifestBytes,
      manifestDigest: built.manifestDigest,
      manifestMediaType: built.manifestMediaType,
    };
  }

  async function publish(world: World): Promise<AdapterResult> {
    const published = await publishWithClient(
      world,
      `docker-${client.name}-publish-${world.scenario.id}`,
    );
    const { packageName: image, version: tag } = world.publishTarget;
    // The raw companion probe of `docker.ts`'s `publish`: a byte-identical re-PUT under the same tag
    // with the same credential, so the outcome is the HTTP status the registry gave, not an exit code.
    const rawRes = await rawPutManifest(
      world.repoName,
      world.credential,
      image,
      tag,
      published.manifestBytes,
      published.manifestMediaType,
    );
    return {
      outcome: outcomeForStatus(rawRes.status),
      httpStatus: rawRes.status,
      clientExitCode: published.exitCode,
      command: published.command,
      contentSha256: stripSha256Prefix(published.manifestDigest),
    };
  }

  async function seedPublish(world: World): Promise<SeedResult> {
    const published = await publishWithClient(
      world,
      `docker-${client.name}-seed-${world.scenario.id}`,
    );
    if (published.exitCode !== 0) {
      throw new Error(
        `docker[${client.name}] adapter: pre-publish for scenario "${world.scenario.id}" failed ` +
          `unexpectedly (${client.name} exit ${published.exitCode}); its "consume: ok" expectation ` +
          'depends on this image actually existing.',
      );
    }
    return { contentSha256: stripSha256Prefix(published.manifestDigest) };
  }

  async function resolve(world: World): Promise<AdapterResult> {
    const { home, work } = await isolatedWorkDir(`docker-${client.name}-con-${world.scenario.id}`);
    const { packageName: image, version: tag } = world.consumeTarget;
    await client.renderAuth(home, world.credential);
    const pulledDir = path.join(work, 'pulled');

    const result = await run(
      client.name,
      client.pullArgs(imageRef(world.repoName, image, tag), pulledDir),
      {
        cwd: work,
        env: client.env(home),
        timeoutMs: CONSUME_TIMEOUT_MS,
        redact: secretsOf(world.credential),
        label: `docker-${client.name}-consume-${world.scenario.id}`,
      },
    );

    const rawRes = await rawGetManifest(world.repoName, world.credential, image, tag);
    const resolved = await client.readPulled(pulledDir);
    return {
      outcome: outcomeForStatus(rawRes.status),
      httpStatus: rawRes.status,
      clientExitCode: result.exitCode,
      command: result.command,
      contentSha256: resolved?.hex,
      resolvedFile: resolved?.file,
    };
  }

  return {
    ...dockerAdapter,
    label: client.name,
    tags: [`@${client.name}`],
    client: { name: client.name, publishVerb: client.publishVerb, consumeVerb: client.consumeVerb },
    publish,
    resolve,
    seedPublish,
  };
}

/** One private `HOME` with the credential rendered for `client`, and `client` runs against it. */
export interface ClientSession {
  readonly home: string;
  readonly work: string;
  /** Runs the client with `args`: the whole command line after the binary name. */
  run(args: string[], label: string, opts?: { input?: string }): Promise<RunResult>;
}

export async function openSession(
  client: CopyClient,
  credential: MaterializedCredential,
  label: string,
): Promise<ClientSession> {
  const { home, work } = await isolatedWorkDir(label);
  await client.renderAuth(home, credential);
  return {
    home,
    work,
    run: (args, stepLabel, opts) =>
      run(client.name, args, {
        cwd: work,
        env: client.env(home),
        timeoutMs: 120_000,
        redact: secretsOf(credential),
        label: stepLabel,
        ...(opts?.input !== undefined ? { input: opts.input } : {}),
      }),
  };
}
