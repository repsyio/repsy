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
 */
import { buildImageContent } from '../../clients/docker-image.js';
import { rawPutManifest, rawUploadBlob } from '../../clients/docker-raw.js';
import { adminCredential } from '../../clients/raw-http.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

export const seedDocker: PackageSeeder = async (repoName, ctx, opts) => {
  const image = opts.name ?? defaultPackageName('docker', ctx.runId, opts.index);
  const tag = opts.version ?? DEFAULT_VERSION;
  const admin = adminCredential();

  // The marker makes every (image, tag) a distinct image, so two tags are two manifests.
  const built = buildImageContent({ marker: `e2e ${image}:${tag}` });
  expectPublished(
    await rawUploadBlob(repoName, admin, image, built.configBytes, built.configDigest),
    `config blob of ${image}:${tag}`,
  );
  expectPublished(
    await rawUploadBlob(repoName, admin, image, built.layerBytes, built.layerDigest),
    `layer blob of ${image}:${tag}`,
  );
  expectPublished(
    await rawPutManifest(repoName, admin, image, tag, built.manifestBytes, built.manifestMediaType),
    `manifest ${image}:${tag}`,
  );

  return {
    protocol: 'docker',
    repoName,
    name: image,
    version: tag,
    extra: { digest: built.manifestDigest, configDigest: built.configDigest },
  };
};
