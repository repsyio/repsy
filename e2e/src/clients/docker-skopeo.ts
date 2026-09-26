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
 * `skopeo` (containers/image, daemonless) as the second Docker client family (RPS-1478 part B): the
 * scenario-loop adapter (`skopeoAdapter`, the whole catalog through `skopeo copy`, see
 * `docker-copy-adapter.ts`) and the command builders the client-specific tests use
 * (`tests/docker/skopeo.spec.ts`).
 *
 * How this client differs from `crane` on the wire (probed live against a Repsy Docker repo):
 *  - It pings `https://<host>/v2/` first and falls back to `http://` only with `--tls-verify=false`
 *    (`docker-tls.ts`), then follows the Bearer challenge: `GET /v2/token?account=<user>&scope=...
 *    &service=repsy` with Basic credentials.
 *  - Its token scope is `repository:<repo>/<image>:pull,push` for a copy INTO the registry and `:pull`
 *    out of it, but `:*` for `skopeo delete`, which Repsy's delete-scope rule (RPS-1434) accepts
 *    for MANAGE credentials at the first request: no `insufficient_scope` round trip.
 *  - A blob goes up as `POST` + one `PATCH` + `PUT ?digest=` (chunked), the manifest last.
 *
 * Credentials: a rendered `auth.json` in the docker `config.json` shape (the same `renderDockerConfig`
 * the `crane` tests use), pointed at by `REGISTRY_AUTH_FILE`: never `--creds`, so no secret is in
 * argv. `--insecure-policy` skips `policy.json`/`registries.d` (the runner image has none), which
 * needs no other system configuration for a plain-HTTP registry.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { clientEnv } from './client-env.js';
import { copyAdapter, type CopyClient } from './docker-copy-adapter.js';
import { skopeoTlsFlags } from './docker-tls.js';
import { renderDockerConfig, stripSha256Prefix } from './docker.js';
import { sha256Hex } from './docker-raw.js';

export function skopeoEnv(home: string): NodeJS.ProcessEnv {
  return clientEnv(home, { REGISTRY_AUTH_FILE: path.join(home, '.docker', 'config.json') });
}

/** `docker://<ref>` for a registry reference `<host>/<repo>/<image>[:tag|@digest]`. */
export function dockerUrl(ref: string): string {
  return `docker://${ref}`;
}

/** The digests of every blob a manifest names (`config`, then `layers`), as `sha256:<hex>`. */
function blobDigestsOf(manifest: Buffer): string[] {
  const parsed = JSON.parse(manifest.toString('utf8')) as {
    config?: { digest?: string };
    layers?: { digest?: string }[];
  };
  return [parsed.config?.digest, ...(parsed.layers ?? []).map((l) => l.digest)].filter(
    (d): d is string => typeof d === 'string',
  );
}

/**
 * The image `skopeo copy docker://... dir:<dir>` left in `dir`: the `dir:` transport keeps the
 * registry's own manifest BYTES (`manifest.json`, no conversion to OCI, which the `oci:` transport
 * would do to a Docker-schema2 manifest) and one file per blob named by its hex digest. Checks the
 * blobs against their names, the same "prove the bytes are what they claim to be" step as
 * `docker.ts`'s `readResolvedImage`.
 */
export async function readSkopeoDir(
  dir: string,
): Promise<{ hex: string; file: string } | undefined> {
  let manifest: Buffer;
  try {
    manifest = await fs.readFile(path.join(dir, 'manifest.json'));
  } catch {
    return undefined;
  }
  for (const digest of blobDigestsOf(manifest)) {
    const hex = stripSha256Prefix(digest);
    let bytes: Buffer;
    try {
      bytes = await fs.readFile(path.join(dir, hex));
    } catch {
      return undefined;
    }
    if (sha256Hex(bytes) !== hex) {
      throw new Error(`skopeo dir: blob ${hex} does not match its own filename`);
    }
  }
  return { hex: sha256Hex(manifest), file: path.join('pulled', 'manifest.json') };
}

export const skopeoClient: CopyClient = {
  name: 'skopeo',
  publishVerb: 'copy (to docker://)',
  consumeVerb: 'copy (from docker://)',
  env: skopeoEnv,
  renderAuth: async (home, credential) => {
    await renderDockerConfig(home, credential);
  },
  pushArgs: (built, ref) => [
    'copy',
    '--insecure-policy',
    '--preserve-digests',
    ...skopeoTlsFlags('dest'),
    `oci:${built.dir}`,
    dockerUrl(ref),
  ],
  pullArgs: (ref, destDir) => [
    'copy',
    '--insecure-policy',
    ...skopeoTlsFlags('src'),
    dockerUrl(ref),
    `dir:${destDir}`,
  ],
  readPulled: readSkopeoDir,
};

export const skopeoAdapter = copyAdapter(skopeoClient);
