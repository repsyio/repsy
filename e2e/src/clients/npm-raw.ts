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
 * Raw HTTP helpers for the npm repository protocol, next to the real-client adapter (`npm.ts`),
 * built on the protocol-agnostic pieces in `raw-http.ts`. They exist for the same two jobs
 * `maven-raw.ts` exists for: pin the exact HTTP status a real client hides behind its exit code (or,
 * for npm, behind a generic network-error message), and look at what a (refused) publish did or did
 * not leave in the repository, always with the admin credential.
 *
 * Layout/wire facts these helpers rely on, read from the server source
 * (`repsy-protocols/npm/.../AbstractNpm{ProtocolFacade,StorageService}.java`,
 * `AbstractNpmPackage{Publish,Metadata,Download}ProtocolMethodHandler.java`) and confirmed live
 * (see the e2e run report):
 *  - Publish: `PUT /<repoName>/<escapedName>` where `escapedName` is the package name with a `/`
 *    percent-encoded (`@scope%2fname`) -- the same escaping a real npm client sends (`lib/utils/
 *    get-publish-config.js`/`libnpmpublish`), and how `NpmPathParser` + `ExtractPath.extractPathVars`
 *    expect it (Spring decodes `%2f` back to `/` before the path parser sees it).
 *  - Packument GET: same path, `Accept: application/vnd.npm.install-v1+json` for the abbreviated
 *    form (`PackageUtils.isRequestedAbbreviatedMetadata`).
 *  - Tarball GET (canonical): `GET /<repoName>/<packagePath>/-/<tarballFilename>`, matched by
 *    `AbstractNpmPackageDownloadProtocolMethodHandler`'s `^/(.+?)/-/(.+)`; `packagePath` here uses a
 *    literal, un-encoded `/` (`@scope/name`, not `@scope%2fname`) since it is genuinely two path
 *    segments, not one escaped one. `tarballFilename` never carries the scope
 *    (`PackageUtils.getTarballFilename(packageName, version)` is given the bare name), matching real
 *    npm's own convention (`@scope/name` -> `.../-/name-version.tgz`).
 *  - `PackageUtils.fixTarballUrl` (RPS-1205) rewrites the *stored* `dist.tarball` at publish time by
 *    splicing the repo name into the path at a fixed offset that assumes a different (cloud,
 *    multi-tenant) URL shape than Repsy OS's `/<repoName>/<packagePath>/-/<file>`; on OS this always
 *    produces a wrong path. `rawGetTarballByUrl` fetches that (likely broken) URL exactly as a real
 *    npm client would, so a test can compare it against `rawGetTarballCanonical`'s real status.
 *
 * The override rule (`AbstractNpmProtocolFacade.publish`): a version that already exists is refused
 * with `403 packageVersionAlreadyExists` when `allowOverride` is off; a NEW version of an existing
 * package is always accepted regardless of `allowOverride`, since only re-publishing an *existing*
 * version is checked. `PackageUtils.extractVersionNameFromPayload` refuses a malformed version with
 * `400 invalidPackageVersion` before anything is stored.
 */
import { createHash } from 'node:crypto';

import { env } from '../env.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import {
  adminCredential,
  msgIdOf,
  type RawResponse,
  sha256Hex,
  withBackoff429Response,
} from './raw-http.js';

export { adminCredential, sha256Hex, type RawResponse };

const ABBREVIATED_ACCEPT = 'application/vnd.npm.install-v1+json';

/**
 * An npm auth header from a `MaterializedCredential`: `kind === 'token'` (a deploy token) sends
 * `Authorization: Bearer <token>`, matching how the harness's `.npmrc` template wires a token via
 * `_authToken` (correction #4); `kind === 'password'` sends Basic, matching `_auth`; no credential
 * (`anonymous`) sends no header at all -- a real npm client with no configured credential never
 * makes the request in the first place (`ENEEDAUTH`), which is why the fixture/adapter never expects
 * a client exit 0 for it, but the raw probe still needs a header-less request to pin the real status.
 */
export function npmAuthHeader(credential: MaterializedCredential): Record<string, string> {
  if (credential.transport !== 'basic') {
    return {};
  }
  if (credential.kind === 'token') {
    return { Authorization: `Bearer ${credential.password ?? ''}` };
  }
  const basic = Buffer.from(`${credential.username ?? ''}:${credential.password ?? ''}`).toString(
    'base64',
  );
  return { Authorization: `Basic ${basic}` };
}

/** `@scope/name` -> `@scope%2fname`, `name` -> `name` -- what a real npm client PUTs/GETs. */
export function encodePackageNameForUrl(packageName: string): string {
  return encodeURIComponent(packageName).replace(/^%40/, '@');
}

/** `@scope/name` -> `name`, `name` -> `name`: the scope never appears in a tarball's file name. */
export function bareName(packageName: string): string {
  const slash = packageName.indexOf('/');
  return slash === -1 ? packageName : packageName.slice(slash + 1);
}

export function tarballFilename(packageName: string, version: string): string {
  return `${bareName(packageName)}-${version}.tgz`;
}

/** The canonical, repo-relative download path: `<packagePath>/-/<tarballFilename>`. */
export function tarballPath(packageName: string, version: string): string {
  return `${packageName}/-/${tarballFilename(packageName, version)}`;
}

function repoUrl(repoName: string): string {
  return `${env.repoBaseUrl}/${repoName}/`;
}

function packagePutGetUrl(repoName: string, packageName: string): string {
  return `${repoUrl(repoName)}${encodePackageNameForUrl(packageName)}`;
}

/**
 * The publish document shape libnpmpublish sends (plan section B1): `PUT <registry>/<escapedName>`
 * with `_id`/`name`/`description`/`dist-tags`/`versions`/`_attachments`. `tarballBytes` are used
 * as-is (a real client's `npm pack` output, or arbitrary bytes for a raw-only probe -- Repsy never
 * validates that a stored "tarball" is actually a valid tar+gzip stream, the same way maven's raw
 * seeds use plain strings as "jar" bytes in `upload-rules.spec.ts`).
 */
export function buildPublishDocument(opts: {
  repoName: string;
  packageName: string;
  version: string;
  tarballBytes: Buffer;
  tag?: string;
  description?: string;
}): Record<string, unknown> {
  const tag = opts.tag ?? 'latest';
  const filename = tarballFilename(opts.packageName, opts.version);
  const tarball = new URL(tarballPath(opts.packageName, opts.version), repoUrl(opts.repoName)).href;
  const shasum = createHash('sha1').update(opts.tarballBytes).digest('hex');
  const integrity = `sha512-${createHash('sha512').update(opts.tarballBytes).digest('base64')}`;
  const description = opts.description ?? `e2e ${opts.packageName}@${opts.version}`;

  const versionManifest = {
    name: opts.packageName,
    version: opts.version,
    description,
    // Deliberately present (never left absent): `PackageUtils.liftFieldsToTopLevel` defaults a
    // version's absent `keywords` to a native `String[]` on the top-level packument, and
    // `NpmPackageServiceImpl.addKeywords` (reached via `updateVersionFromMetadata`, the "re-publish
    // an EXISTING version" DB path) then casts that top-level value to `ArrayList<String>` -- which
    // throws a `ClassCastException` (swallowed by `AbstractNpmProtocolFacade.publish`'s catch-all
    // into a generic `400 badRequest`) for ANY republish/override of a version whose manifest has no
    // `keywords`. This is a genuine backend bug distinct from RPS-1205, not something this harness
    // works around by skipping the check -- it is out of scope to fix here (see the plan), so every
    // manifest this adapter sends simply includes the field, the same way a real npm client's own
    // normalized `package.json` almost always does.
    keywords: [] as string[],
    dist: { integrity, shasum, tarball },
  };

  return {
    _id: opts.packageName,
    name: opts.packageName,
    description,
    'dist-tags': { [tag]: opts.version },
    versions: { [opts.version]: versionManifest },
    _attachments: {
      [filename]: {
        content_type: 'application/octet-stream',
        data: opts.tarballBytes.toString('base64'),
        length: opts.tarballBytes.length,
      },
    },
  };
}

async function rawRequest(
  url: string,
  init: { method?: string; headers?: Record<string, string>; body?: string },
): Promise<RawResponse> {
  return withBackoff429Response(async () => {
    const res = await fetch(url, init);
    const bytes = Buffer.from(await res.arrayBuffer());
    return { status: res.status, msgId: msgIdOf(bytes), body: bytes };
  });
}

/** Raw `PUT` of a publish document, exactly what a real npm client's `npm publish` sends. */
export async function rawPublish(
  repoName: string,
  credential: MaterializedCredential,
  packageName: string,
  document: Record<string, unknown>,
): Promise<RawResponse> {
  return rawRequest(packagePutGetUrl(repoName, packageName), {
    method: 'PUT',
    headers: { ...npmAuthHeader(credential), 'Content-Type': 'application/json' },
    body: JSON.stringify(document),
  });
}

/** Raw `GET` of a package's packument (full, or abbreviated with the npm install-v1 Accept header). */
export async function rawGetPackument(
  repoName: string,
  credential: MaterializedCredential,
  packageName: string,
  abbreviated = false,
): Promise<RawResponse> {
  const headers = { ...npmAuthHeader(credential) };
  if (abbreviated) {
    (headers as Record<string, string>).Accept = ABBREVIATED_ACCEPT;
  }
  return rawRequest(packagePutGetUrl(repoName, packageName), { headers });
}

/** Raw `GET` of an arbitrary repo-relative path (tarball by its canonical, real stored path). */
export async function rawGetPath(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${relPath}`, { headers: npmAuthHeader(credential) });
}

/** Raw `GET` of the tarball by its canonical, real stored path. */
export async function rawGetTarballCanonical(
  repoName: string,
  credential: MaterializedCredential,
  packageName: string,
  version: string,
): Promise<RawResponse> {
  return rawGetPath(repoName, credential, tarballPath(packageName, version));
}

/**
 * Raw `GET` of an absolute URL (a packument's own `dist.tarball`, likely broken by RPS-1205), with
 * the same credential a real npm client's follow-up tarball fetch would use.
 */
export async function rawGetTarballByUrl(
  url: string,
  credential: MaterializedCredential,
): Promise<RawResponse> {
  return rawRequest(url, { headers: npmAuthHeader(credential) });
}

/** Reads `versions`/`dist-tags` off a packument JSON body already known to be `200`. */
export function parsePackument(body: Buffer): {
  versions: Record<string, { dist?: { tarball?: string } }>;
  distTags: Record<string, string>;
} {
  const parsed = JSON.parse(body.toString('utf8')) as {
    versions?: Record<string, { dist?: { tarball?: string } }>;
    'dist-tags'?: Record<string, string>;
  };
  return { versions: parsed.versions ?? {}, distTags: parsed['dist-tags'] ?? {} };
}
