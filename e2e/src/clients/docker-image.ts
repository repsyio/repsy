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
 * Builds a tiny, fully hand-assembled OCI image layout directory (step 4a, RPS-294): one gzip
 * layer (a hand-written ustar tar with `e2e-marker.txt` = a random marker), one JSON config, one
 * JSON manifest, `index.json`, `oci-layout` -- deliberately never `docker build`/`crane append`, the
 * nuget/cargo precedent of never shelling out to a package-building tool this harness does not
 * control the exact bytes of (`nuget-raw.ts`'s `buildNupkg`, built with `fflate` instead of `dotnet
 * pack`). `crane push <dir> <ref>` (ggcr's `remote.Write`) sends `img.RawManifest()`/the layout's own
 * stored blob bytes verbatim, so every raw HTTP probe in `docker-raw.ts` can re-PUT/HEAD/GET the very
 * same bytes a real `crane push` sent.
 *
 * Layer/config/manifest facts this builder relies on (see `docker-raw.ts`'s header for the wire-level
 * facts, and `AbstractDockerProtocolTxFacade.extractPlatform` -- README.md's "R12/B5", fixed by
 * RPS-1116):
 *  - For an image-config media type, the config JSON MUST carry `architecture`/`os` (a config
 *    without them now answers a 400 `manifestConfigInvalid`, not a flat 500 -- `unsupportedMediaType`'s
 *    former sibling bug, see README.md's "R12/B5") and `rootfs.diff_ids` (the OCI image-spec's own
 *    `config.md` requirement) -- the sha256 of the UNCOMPRESSED tar, not the gzip.
 *  - Docker's classic media types (`schemaVersion: 2`,
 *    `application/vnd.docker.distribution.manifest.v2+json`) are the default family; OCI media types
 *    (`application/vnd.oci.image.manifest.v1+json`) are opt-in via `family: 'oci'` -- exercised by the
 *    "D1" real-client test (`tests/docker/publish-consume.spec.ts`).
 *  - `oci-layout`/`index.json` follow the OCI image-layout spec (`image-layout.md`) literally: the
 *    index's one descriptor points straight at the image manifest (no intermediate index-of-indexes).
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import zlib from 'node:zlib';

export type MediaTypeFamily = 'docker' | 'oci';

interface MediaTypeSet {
  manifest: string;
  config: string;
  layer: string;
}

export const DOCKER_MEDIA_TYPES: MediaTypeSet = {
  manifest: 'application/vnd.docker.distribution.manifest.v2+json',
  config: 'application/vnd.docker.container.image.v1+json',
  layer: 'application/vnd.docker.image.rootfs.diff.tar.gzip',
};

export const OCI_MEDIA_TYPES: MediaTypeSet = {
  manifest: 'application/vnd.oci.image.manifest.v1+json',
  config: 'application/vnd.oci.image.config.v1+json',
  layer: 'application/vnd.oci.image.layer.v1.tar+gzip',
};

export interface BuiltImage {
  /** The OCI layout directory `crane push <dir> <ref>` reads (ggcr: "if the PATH is a directory, it
   *  will be read as an OCI image layout"). */
  dir: string;
  manifestBytes: Buffer;
  /** `sha256:<hex>`. */
  manifestDigest: string;
  manifestMediaType: string;
  configBytes: Buffer;
  configDigest: string;
  configMediaType: string;
  layerBytes: Buffer;
  layerDigest: string;
  layerMediaType: string;
  /** `sha256:<hex>` of the UNCOMPRESSED tar -- the OCI config's `rootfs.diff_ids` entry. */
  layerDiffId: string;
  marker: string;
}

function sha256(bytes: Buffer): string {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
}

/** A minimal ustar tar (512-byte headers, octal fields, a checksum, two trailing zero blocks) -- no
 *  `tar`/`tar-stream` dependency, the same "own the exact bytes" reasoning as `docker-image.ts`'s
 *  file header. `mtime` is always 0 (deterministic; nothing here needs a real timestamp). */
export function buildTar(entries: { name: string; data: Buffer }[]): Buffer {
  const blocks: Buffer[] = [];

  for (const entry of entries) {
    const header = Buffer.alloc(512);
    header.write(entry.name, 0, 'utf8');
    header.write('0000644\0', 100, 'ascii'); // mode
    header.write('0000000\0', 108, 'ascii'); // uid
    header.write('0000000\0', 116, 'ascii'); // gid
    header.write(`${entry.data.length.toString(8).padStart(11, '0')}\0`, 124, 'ascii'); // size
    header.write('00000000000\0', 136, 'ascii'); // mtime
    header.write('        ', 148, 'ascii'); // checksum placeholder (8 spaces)
    header.write('0', 156, 'ascii'); // typeflag: regular file
    header.write('ustar', 257, 'ascii');
    header.write('00', 263, 'ascii'); // ustar version

    let checksum = 0;
    for (const byte of header) {
      checksum += byte;
    }
    header.write(`${checksum.toString(8).padStart(6, '0')}\0 `, 148, 'ascii');

    blocks.push(header);
    const padded = Buffer.alloc(Math.ceil(entry.data.length / 512) * 512);
    entry.data.copy(padded);
    blocks.push(padded);
  }

  blocks.push(Buffer.alloc(1024)); // two zero blocks terminate the archive
  return Buffer.concat(blocks);
}

/** Everything `buildImage` returns except where it wrote the layout. */
export type ImageContent = Omit<BuiltImage, 'dir'>;

/**
 * The in-memory half of `buildImage`: the layer, config and manifest bytes and digests, with no
 * file system involved. A raw-HTTP seed (`rawUploadBlob` + `rawPutManifest`) needs nothing else, and
 * `buildImage` below writes exactly these bytes into an OCI layout for `crane push`.
 */
export function buildImageContent(opts: {
  marker: string;
  family?: MediaTypeFamily;
  os?: string;
  arch?: string;
}): ImageContent {
  const types = opts.family === 'oci' ? OCI_MEDIA_TYPES : DOCKER_MEDIA_TYPES;
  const os = opts.os ?? 'linux';
  const arch = opts.arch ?? 'amd64';

  const tar = buildTar([{ name: 'e2e-marker.txt', data: Buffer.from(opts.marker, 'utf8') }]);
  const layerDiffId = sha256(tar);
  const layerBytes = zlib.gzipSync(tar, { level: 6 });
  const layerDigest = sha256(layerBytes);

  const configObj = {
    architecture: arch,
    os,
    config: {},
    rootfs: { type: 'layers', diff_ids: [layerDiffId] },
    history: [{ created: '2026-01-01T00:00:00Z', created_by: 'repsy-e2e' }],
  };
  const configBytes = Buffer.from(JSON.stringify(configObj), 'utf8');
  const configDigest = sha256(configBytes);

  const manifestObj = {
    schemaVersion: 2,
    mediaType: types.manifest,
    config: { mediaType: types.config, digest: configDigest, size: configBytes.length },
    layers: [{ mediaType: types.layer, digest: layerDigest, size: layerBytes.length }],
  };
  const manifestBytes = Buffer.from(JSON.stringify(manifestObj), 'utf8');
  const manifestDigest = sha256(manifestBytes);

  return {
    manifestBytes,
    manifestDigest,
    manifestMediaType: types.manifest,
    configBytes,
    configDigest,
    configMediaType: types.config,
    layerBytes,
    layerDigest,
    layerMediaType: types.layer,
    layerDiffId,
    marker: opts.marker,
  };
}

/**
 * Builds a fresh OCI image layout under `opts.dir` (created if missing) and returns every byte/digest
 * a caller needs, both to hand to `crane push` (the directory alone) and to build raw-HTTP probes with
 * the exact same bytes (`docker-raw.ts`'s `rawPutManifest`/`rawUploadBlob`).
 */
export async function buildImage(opts: {
  dir: string;
  marker: string;
  family?: MediaTypeFamily;
  os?: string;
  arch?: string;
}): Promise<BuiltImage> {
  const content = buildImageContent(opts);

  const blobsDir = path.join(opts.dir, 'blobs', 'sha256');
  await fs.mkdir(blobsDir, { recursive: true });
  await fs.writeFile(
    path.join(blobsDir, content.configDigest.slice('sha256:'.length)),
    content.configBytes,
  );
  await fs.writeFile(
    path.join(blobsDir, content.layerDigest.slice('sha256:'.length)),
    content.layerBytes,
  );
  await fs.writeFile(
    path.join(blobsDir, content.manifestDigest.slice('sha256:'.length)),
    content.manifestBytes,
  );

  await fs.writeFile(
    path.join(opts.dir, 'oci-layout'),
    JSON.stringify({ imageLayoutVersion: '1.0.0' }),
    'utf8',
  );
  await fs.writeFile(
    path.join(opts.dir, 'index.json'),
    JSON.stringify({
      schemaVersion: 2,
      mediaType: 'application/vnd.oci.image.index.v1+json',
      manifests: [
        {
          mediaType: content.manifestMediaType,
          digest: content.manifestDigest,
          size: content.manifestBytes.length,
        },
      ],
    }),
    'utf8',
  );

  return { dir: opts.dir, ...content };
}

export interface BuiltIndex {
  /** The OCI layout directory: every child's blobs, the index manifest, an `index.json` naming the index. */
  dir: string;
  indexBytes: Buffer;
  /** `sha256:<hex>`. */
  indexDigest: string;
  indexMediaType: string;
  children: { os: string; arch: string; content: ImageContent }[];
}

/**
 * A multi-platform layout (an image index / manifest list over one hand-built image per platform),
 * for the real clients that copy whole indexes (`skopeo copy --all`, `regctl image copy`). Each
 * child gets its own layer marker (`<marker>-<arch>`), so no two children share a digest; the
 * layout's `index.json` names the INDEX, not a child, like a real multi-arch layout.
 */
export async function buildIndexImage(opts: {
  dir: string;
  marker: string;
  platforms: { os: string; arch: string }[];
  family?: MediaTypeFamily;
}): Promise<BuiltIndex> {
  const children = opts.platforms.map(({ os, arch }) => ({
    os,
    arch,
    content: buildImageContent({
      marker: `${opts.marker}-${arch}`,
      family: opts.family,
      os,
      arch,
    }),
  }));

  const blobsDir = path.join(opts.dir, 'blobs', 'sha256');
  await fs.mkdir(blobsDir, { recursive: true });
  const write = (digest: string, bytes: Buffer) =>
    fs.writeFile(path.join(blobsDir, digest.slice('sha256:'.length)), bytes);
  for (const { content } of children) {
    await write(content.configDigest, content.configBytes);
    await write(content.layerDigest, content.layerBytes);
    await write(content.manifestDigest, content.manifestBytes);
  }

  const indexMediaType =
    opts.family === 'oci'
      ? 'application/vnd.oci.image.index.v1+json'
      : 'application/vnd.docker.distribution.manifest.list.v2+json';
  const indexBytes = Buffer.from(
    JSON.stringify({
      schemaVersion: 2,
      mediaType: indexMediaType,
      manifests: children.map(({ os, arch, content }) => ({
        mediaType: content.manifestMediaType,
        digest: content.manifestDigest,
        size: content.manifestBytes.length,
        platform: { architecture: arch, os },
      })),
    }),
    'utf8',
  );
  const indexDigest = sha256(indexBytes);
  await write(indexDigest, indexBytes);

  await fs.writeFile(
    path.join(opts.dir, 'oci-layout'),
    JSON.stringify({ imageLayoutVersion: '1.0.0' }),
    'utf8',
  );
  await fs.writeFile(
    path.join(opts.dir, 'index.json'),
    JSON.stringify({
      schemaVersion: 2,
      mediaType: 'application/vnd.oci.image.index.v1+json',
      manifests: [{ mediaType: indexMediaType, digest: indexDigest, size: indexBytes.length }],
    }),
    'utf8',
  );

  return { dir: opts.dir, indexBytes, indexDigest, indexMediaType, children };
}
