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

/**
 * Docker seeder: the config and layer blobs (`POST` + `PUT ?digest=`) and then the image manifest
 * under the tag, all through the registry token hop as the admin. `opts.name` is the image name
 * (one path segment), `opts.version` the tag. `extra.digest` is the manifest digest.
 *
 * With `opts.docker.platforms` (RPS-1627) the tag names an OCI image INDEX instead, the way a
 * multi-arch `docker buildx --push` leaves it: one OCI image manifest per platform (its own config and
 * layer blobs, so no two share a digest) is pushed BY DIGEST, then the index listing them is pushed
 * under the tag. `opts.docker.untagged` more manifests are pushed by digest and listed by nothing, so
 * the image keeps that many untagged manifests. `extra` then also carries, per platform `os/arch`,
 * `digest.<platform>`, `configDigest.<platform>`, `layerDigest.<platform>` and `size.<platform>` (config plus
 * layer bytes, what the panel adds up). With `untagged`, `extra` has `untaggedDigests`, `untaggedLayerDigests`
 * (comma separated) and `untaggedSize` (their config plus layer bytes together).
 */
import {
  buildImageContent,
  buildIndexContent,
  type ImageContent,
} from '../../clients/docker-image.js';
import { rawPutManifest, rawUploadBlob } from '../../clients/docker-raw.js';
import { adminCredential } from '../../clients/raw-http.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

/** Docker-only seed options (`SeedPackageOptions.docker`). */
export interface DockerSeedOptions {
  /**
   * `os/arch` strings (`linux/amd64`): the tag is an OCI image index over one manifest per platform,
   * instead of a single-platform manifest.
   */
  platforms?: readonly string[];
  /** Manifests pushed by digest that no tag and no index reaches. Default 0. */
  untagged?: number;
}

/** The bytes a platform's size cell adds up: its config and its layer. */
export function imageBytes(content: ImageContent): number {
  return content.configBytes.length + content.layerBytes.length;
}

export const seedDocker: PackageSeeder = async (repoName, ctx, opts) => {
  const image = opts.name ?? defaultPackageName('docker', ctx.runId, opts.index);
  const tag = opts.version ?? DEFAULT_VERSION;
  const admin = adminCredential();
  const platforms = opts.docker?.platforms ?? [];
  const untaggedCount = opts.docker?.untagged ?? 0;

  const pushImage = async (content: ImageContent, reference: string, what: string) => {
    expectPublished(
      await rawUploadBlob(repoName, admin, image, content.configBytes, content.configDigest),
      `config blob of ${what}`,
    );
    expectPublished(
      await rawUploadBlob(repoName, admin, image, content.layerBytes, content.layerDigest),
      `layer blob of ${what}`,
    );
    expectPublished(
      await rawPutManifest(
        repoName,
        admin,
        image,
        reference,
        content.manifestBytes,
        content.manifestMediaType,
      ),
      `manifest ${what}`,
    );
  };

  const extra: Record<string, string> = {};

  // Untagged manifests: pushed by digest, listed by nothing. OCI family, so their config is an OCI one.
  const untagged: string[] = [];
  const untaggedLayers: string[] = [];
  let untaggedSize = 0;
  for (let n = 1; n <= untaggedCount; n += 1) {
    const content = buildImageContent({
      marker: `e2e ${image}:${tag} untagged ${n}`,
      family: 'oci',
    });
    await pushImage(content, content.manifestDigest, `${image}@${content.manifestDigest}`);
    untagged.push(content.manifestDigest);
    untaggedLayers.push(content.layerDigest);
    untaggedSize += imageBytes(content);
  }
  if (untaggedCount > 0) {
    extra['untaggedDigests'] = untagged.join(',');
    extra['untaggedLayerDigests'] = untaggedLayers.join(',');
    extra['untaggedSize'] = String(untaggedSize);
  }

  if (platforms.length === 0) {
    // The marker makes every (image, tag) a distinct image, so two tags are two manifests.
    const built = buildImageContent({ marker: `e2e ${image}:${tag}` });
    await pushImage(built, tag, `${image}:${tag}`);

    return {
      protocol: 'docker',
      repoName,
      name: image,
      version: tag,
      extra: { ...extra, digest: built.manifestDigest, configDigest: built.configDigest },
    };
  }

  const index = buildIndexContent({
    marker: `e2e ${image}:${tag}`,
    family: 'oci',
    platforms: platforms.map((platform) => {
      const [os, arch] = platform.split('/');
      if (!os || !arch) {
        throw new Error(`docker seed: platform "${platform}" is not os/arch`);
      }
      return { os, arch };
    }),
  });
  for (const { os, arch, content } of index.children) {
    await pushImage(
      content,
      content.manifestDigest,
      `${image}@${content.manifestDigest} (${os}/${arch})`,
    );
    extra[`digest.${os}/${arch}`] = content.manifestDigest;
    extra[`configDigest.${os}/${arch}`] = content.configDigest;
    extra[`layerDigest.${os}/${arch}`] = content.layerDigest;
    extra[`size.${os}/${arch}`] = String(imageBytes(content));
  }
  expectPublished(
    await rawPutManifest(repoName, admin, image, tag, index.indexBytes, index.indexMediaType),
    `index ${image}:${tag}`,
  );

  return {
    protocol: 'docker',
    repoName,
    name: image,
    version: tag,
    extra: { ...extra, digest: index.indexDigest, platforms: platforms.join(',') },
  };
};
