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
 * Raw HTTP helpers for the PyPI protocol, next to the real-client adapter (`pypi.ts`), built on
 * `raw-http.ts`, the PyPI analogue of `nuget-raw.ts`/`cargo-raw.ts`. Every wire fact below was read
 * from the server source (`repsy-protocols/pypi/**`, `repsy-backend/.../protocols/pypi/**`) and then
 * confirmed live against a running instance (see `README.md`'s "PyPI runner" section for the raw
 * evidence and the H-numbers this file's comments reference).
 *
 *  - Publish: `POST /<repoName>/` (repo ROOT -- `AbstractPypiPackageUploadProtocolMethodHandler
 *    .UPLOAD_PATTERN = ^/?$`, so `/<repo>` with no trailing slash matches too, confirmed live/H12) --
 *    the request must already be `multipart/form-data`, or the path parser itself returns empty and
 *    the router falls through to **404 `unknownPath`**, not 400 (confirmed live/H11; there is no
 *    other handler on `POST /<repo>/`). The file part MUST be named `content`
 *    (`multipartRequest.getFile("content")`), else a bodyless `400` (confirmed live/H11). Every other
 *    multipart field is collected and Jackson-mapped onto `PackageUploadForm`
 *    (`PackageUtils.parseUploadForm`); unknown keys (`:action`, `protocol_version`, `dynamic`,
 *    `license_file`, `attestations`, ...) are silently ignored -- Jackson 3's
 *    `FAIL_ON_UNKNOWN_PROPERTIES` defaults to `false` -- confirmed live with the REAL twine form
 *    (H1): `200`, empty body, twine exit 0.
 *  - Upload pipeline order (`AbstractPypiProtocolFacade.uploadPackage`/`savePackage`,
 *    `AbstractPypiStorageService.writePackageArchive`/`isPackageFileExist`):
 *      1. `normalizePackageName` (PEP 503: `[-_.]+` -> `-`, lower-cased).
 *      2. `PackageStorageUtils.checkArchiveFilename`: the filename must fully match
 *         `NAME-VERSION(-tag)*.(tar.gz|whl|zip)` with VERSION the PEP 440 **canonical** grammar
 *         (`ReleaseVersion`'s own `NORMALIZED_VERSION_PATTERN`) -- else `400 archiveFileNameInvalid`.
 *      3. `checkOverridePermission` (skipped when `allowOverride: true`): refuses with
 *         `403 fileAlreadyExists` only when `isPackageFileExist` is true, which itself is true only
 *         when `isFileBelongsRelease(filename, version)` (the RAW FORM `version`, compared against
 *         the version RE-EXTRACTED from the filename via `ReleaseVersion.of`) AND the storage path
 *         already exists -- so a form `version` that does not match the filename's own version makes
 *         an existing file overwritable even with `allowOverride: false` (P3, confirmed live).
 *      4. `writePackageArchive` (storage: the file, THEN a `.sha256` sidecar containing
 *         `uploadForm.getSha256_digest()` VERBATIM, never recomputed/verified -- a missing digest is
 *         a `500` NPE, a wrong one is silently served, P5, both confirmed live) runs BEFORE
 *         `PypiPackageServiceImpl.addOrUpdateRelease`, where `ReleaseVersion.of(form.version)` can
 *         still throw `400 badVersionString` for an invalid (non-canonical, non-PEP-440) version
 *         string -- so a validation failure AFTER the storage write leaves an orphaned, downloadable
 *         file+sidecar with no DB row at all (P4, confirmed live; RPS-1124 already tracks this
 *         "storage before DB" family for PyPI, see `catalog.ts`'s file header for the cargo/nuget
 *         analogues -- comment there, do not file a new ticket).
 *    Success: **`200`, empty body** (`ResponseEntity.ok().build()`), the only protocol in this
 *    harness whose accepted publish is not `201`.
 *  - Project page (PEP 503 HTML): `GET /<repoName>/simple/<name>/`, `permission: READ`. A `307`
 *    redirect to the PEP-503-normalized name (with a trailing slash) is answered FIRST whenever the
 *    requested name is not already normalized or lacks the trailing slash (confirmed live/H14).
 *    Otherwise: `404 packageNotFound` when the package has no DB row at all; else one `<a href=...>`
 *    per stored file (skipping `*.sha256` sidecars), `href="<repoUri>/<name>/-/<filename>
 *    #sha256=<hash>"` with `<repoUri>` built from the CURRENT request's own scheme/host/port plus
 *    `/<repoName>` (a single repo segment on Repsy OS's single-tenant layout -- confirmed live/H8,
 *    the RPS-1205 analogue does NOT reproduce here), `<`/`>` HTML-escaped in `data-requires-python`
 *    (confirmed live/H9: `>=3.9` renders as `&gt;=3.9`). Served `Content-Type: text/html`.
 *  - Root index: `GET /<repoName>/simple/` (no name) is intercepted by `PypiSimpleHandlerPreProcessor`
 *    (priority 200, after auth) and renders `packages.ftl`, whose `href`s hard-code a cloud-layout
 *    `/pypi/<repoName>/simple/<name>/` prefix that does not exist on Repsy OS (P1) -- confirmed live
 *    (H13): the body IS this malformed HTML, but the response's `Content-Type` is
 *    `application/json`, not `text/html` (a second, narrower quirk this file's own probing turned up
 *    beyond what the plan predicted -- `ResponseEntity.ok(resource)` with no explicit content type,
 *    apparently negotiated to JSON regardless of the client's `Accept`). Harmless for a real `pip`
 *    loop either way: `pip install`/`download` never fetch this page, only `/simple/<project>/`.
 *  - Download: `GET /<repoName>/<name>/-/<filename>` (`<name>` used LITERALLY as the storage
 *    directory, so it must be the normalized name the index itself advertises), `permission: READ`.
 *    `application/octet-stream` + `Content-Disposition: attachment`; `404 itemNotFound` when missing.
 *    Streams the stored bytes verbatim (confirmed live/H3: sha256 of a downloaded file equals
 *    sha256 of the exact bytes twine/a raw POST sent).
 *  - `HEAD` on ANY path under a pypi repo answers `200` empty, existence never checked (P6,
 *    confirmed live/H18: `HEAD` of a path that was never published still answers `200`).
 *  - Auth (`PypiAuthPreProcessor`, priority 100): skipped only for a public-repo READ
 *    (`!privateRepo && !writeOperation`). Otherwise a missing/unparseable `Authorization` is a flat
 *    `401 unAuthorized` + `WWW-Authenticate: Basic realm="Repsy Managed Repository"`.
 *    `PypiAuthComponent.handleBasicAuthWithToken` tries a deploy token by PASSWORD first (username
 *    ignored), then falls back to username/password auth -- so a read-only deploy token attempting a
 *    WRITE is the same flat `401` every other protocol in this harness gives (confirmed live/H5, not
 *    a `403`), while the SAME token's READ (the project page) is `200`. Single-hop Basic, no token
 *    exchange -- matches maven/npm/cargo/nuget/helm's shared auth-outcome pins.
 *  - `releases`/`snapshots` repo settings are never read by any PyPI code (grep-confirmed): a
 *    `.dev0`/`a1`/`.post1` upload is accepted regardless of either switch (H23; `catalog.ts` never
 *    adds `pypi` to the maven/nuget-only `releases`/`snapshots` scenarios, same as docker/helm).
 */
import { createHash } from 'node:crypto';

import { zipSync } from 'fflate';

import { env } from '../env.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import {
  adminCredential,
  authHeader,
  msgIdOf,
  type RawResponse,
  sha256Hex,
  withBackoff429Response,
} from './raw-http.js';

export { adminCredential, authHeader, msgIdOf, sha256Hex, type RawResponse };

function repoUrl(repoName: string): string {
  return `${env.repoBaseUrl}/${repoName}/`;
}

/** `e2e-<runid>-<slugified scenario id>` -- matches `ProtocolAdapter.packageName`'s signature. Every
 *  character `scenario.id` can contain is already alnum/`-`, so this is ALREADY PEP 503-normalized
 *  (`[-_.]+` -> `-`, lower-cased): the storage dir / DB `normalizedName` equal it verbatim, `pip`
 *  requests the project page under its own canonical form, and the server never 307-redirects a
 *  scenario's own publish/consume round trip. */
export function packageName(runId: string, scenario: Scenario): string {
  return `e2e-${runId}-${scenario.id.replace(/[^a-z0-9]/gi, '-').toLowerCase()}`;
}

/** `name.replace(/-/g, '_')` -- the distribution-name spelling PEP 427/625 filenames use (never a
 *  hyphen, and `packageName`'s output never contains `__`, so this never round-trips ambiguously). */
export function distName(name: string): string {
  return name.replace(/-/g, '_');
}

export function wheelFilename(name: string, version: string): string {
  return `${distName(name)}-${version}-py3-none-any.whl`;
}

/** `<repoBaseUrl>/<repo>/` -- what a real `twine upload --repository-url` is given (trailing slash
 *  on purpose, confirmed live/H12 that the no-slash spelling is accepted too, but this is what the
 *  real client itself is pointed at). */
export function uploadUrl(repoName: string): string {
  return repoUrl(repoName);
}

export function simpleRootPath(): string {
  return 'simple/';
}

export function simplePagePath(name: string): string {
  return `simple/${name}/`;
}

export function downloadPath(name: string, fileName: string): string {
  return `${name}/-/${fileName}`;
}

/** The `PIP_INDEX_URL` value a real `pip download`/`pip install` is pointed at: URL-embedded Basic
 *  credentials (percent-encoded, pip's own supported form), or no credentials at all for
 *  `anonymous`. No trailing slash needed (confirmed live/H2: pip resolves `/simple/<project>/`
 *  correctly either way). */
export function indexUrlFor(repoName: string, credential: MaterializedCredential): string {
  const base = `${env.repoBaseUrl}/${repoName}/simple`;
  if (credential.transport !== 'basic' || !credential.username) {
    return base;
  }
  const user = encodeURIComponent(credential.username);
  const pass = encodeURIComponent(credential.password ?? '');
  const url = new URL(base);
  return `${url.protocol}//${user}:${pass}@${url.host}${url.pathname}`;
}

async function rawRequest(
  url: string,
  init: { method?: string; headers?: Record<string, string>; body?: FormData },
): Promise<RawResponse> {
  return withBackoff429Response(async () => {
    const res = await fetch(url, init as RequestInit);
    const bytes = Buffer.from(await res.arrayBuffer());
    return { status: res.status, msgId: msgIdOf(bytes), body: bytes };
  });
}

/** One line of a wheel's `RECORD` file (PEP 376): `<path>,sha256=<base64url, no padding>,<size>`.
 *  Node's `Buffer#toString('base64url')` already omits padding, matching PEP 376's own grammar. */
function recordLine(path: string, content: string): string {
  const hash = createHash('sha256').update(content, 'utf8').digest();
  return `${path},sha256=${hash.toString('base64url')},${Buffer.byteLength(content, 'utf8')}`;
}

/** A hand-built wheel (`fflate.zipSync`, never `python -m build`/`setuptools`/`wheel` -- see this
 *  story's own constraints): a package dir with a marker file, then a `.dist-info` with only the
 *  headers twine 7's `packaging.metadata.parse_email` recognizes (an unrecognised `METADATA` header
 *  raises `InvalidDistribution` client-side), a minimal `WHEEL`, and a `RECORD` covering every other
 *  entry (itself last, with an empty hash/size per PEP 376). */
export interface BuiltWheel {
  name: string;
  version: string;
  distName: string;
  filename: string;
  bytes: Buffer;
  sha256Hex: string;
  requiresPython: string;
}

export function buildWheel(opts: {
  name: string;
  version: string;
  marker?: string;
  requiresPython?: string;
}): BuiltWheel {
  const requiresPython = opts.requiresPython ?? '>=3.9';
  const marker = opts.marker ?? `e2e ${opts.name}@${opts.version}`;
  const dist = distName(opts.name);
  const distInfo = `${dist}-${opts.version}.dist-info`;

  const metadataText =
    'Metadata-Version: 2.1\n' +
    `Name: ${opts.name}\n` +
    `Version: ${opts.version}\n` +
    `Summary: e2e ${opts.name}@${opts.version}\n` +
    `Requires-Python: ${requiresPython}\n`;
  const wheelText =
    'Wheel-Version: 1.0\nGenerator: repsy-e2e\nRoot-Is-Purelib: true\nTag: py3-none-any\n';

  const entries: Record<string, string> = {
    [`${dist}/__init__.py`]: '# e2e marker package\n',
    [`${dist}/e2e_marker.txt`]: `${marker}\n`,
    [`${distInfo}/METADATA`]: metadataText,
    [`${distInfo}/WHEEL`]: wheelText,
  };

  const recordPath = `${distInfo}/RECORD`;
  const recordLines = Object.entries(entries).map(([path, content]) => recordLine(path, content));
  recordLines.push(`${recordPath},,`);
  const recordText = `${recordLines.join('\n')}\n`;

  const zipEntries: Record<string, Uint8Array> = {};
  for (const [path, content] of Object.entries(entries)) {
    zipEntries[path] = new TextEncoder().encode(content);
  }
  zipEntries[recordPath] = new TextEncoder().encode(recordText);

  const bytes = Buffer.from(zipSync(zipEntries, { level: 0 }));

  return {
    name: opts.name,
    version: opts.version,
    distName: dist,
    filename: wheelFilename(opts.name, opts.version),
    bytes,
    sha256Hex: sha256Hex(bytes),
    requiresPython,
  };
}

/** The multipart form a real `twine upload` sends for a wheel (`twine/repository.py`/`wheel.py`):
 *  `:action`/`protocol_version` (ignored server-side, see this file's header), the scalar metadata
 *  fields, `filetype=bdist_wheel`/`pyversion=py3`, `sha256_digest`, and the file as `content`.
 *  Deliberately omits `blake2_256_digest` -- Repsy never reads it (`PackageUploadForm` has the field,
 *  but nothing in the upload pipeline touches it), noted here rather than sent as a no-op. `fetch`'s
 *  own `FormData` handling sets the `multipart/form-data; boundary=...` header. */
export function buildUploadForm(built: BuiltWheel, opts?: { sha256Digest?: string }): FormData {
  const form = new FormData();
  form.append(':action', 'file_upload');
  form.append('protocol_version', '1');
  form.append('metadata_version', '2.1');
  form.append('name', built.name);
  form.append('version', built.version);
  form.append('summary', `e2e ${built.name}@${built.version}`);
  form.append('requires_python', built.requiresPython);
  form.append('filetype', 'bdist_wheel');
  form.append('pyversion', 'py3');
  if (opts?.sha256Digest !== undefined) {
    form.append('sha256_digest', opts.sha256Digest);
  } else {
    form.append('sha256_digest', built.sha256Hex);
  }
  form.append('content', new Blob([new Uint8Array(built.bytes)]), built.filename);
  return form;
}

/** Raw `POST` of a publish, exactly what a real `twine upload` sends (this file's header). */
export async function rawUpload(
  repoName: string,
  credential: MaterializedCredential,
  built: BuiltWheel,
  opts?: { sha256Digest?: string },
): Promise<RawResponse> {
  return rawRequest(uploadUrl(repoName), {
    method: 'POST',
    headers: authHeader(credential),
    body: buildUploadForm(built, opts),
  });
}

/** Raw `GET` of one package's project page, following the 307 (pip's own normalize-and-retry
 *  behaviour), with `credential`'s auth header. */
export async function rawGetSimplePage(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${simplePagePath(name)}`, {
    headers: authHeader(credential),
  });
}

/** Raw `GET` of the project page WITHOUT following a redirect, to pin the 307 `Location` itself
 *  (H14). `name` is passed exactly as given (no trailing slash added), so a non-normalized/no-slash
 *  request actually exercises the redirect. */
export async function rawGetSimplePageNoFollow(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
): Promise<{ status: number; location: string | null }> {
  const res = await fetch(`${repoUrl(repoName)}simple/${name}`, {
    headers: authHeader(credential),
    redirect: 'manual',
  });
  return { status: res.status, location: res.headers.get('location') };
}

/** Raw `GET` of the root `/simple/` index (P1/H13: malformed `/pypi/...` hrefs, `Content-Type:
 *  application/json` despite an HTML body). */
export async function rawGetSimpleRoot(
  repoName: string,
  credential: MaterializedCredential,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${simpleRootPath()}`, { headers: authHeader(credential) });
}

/** Raw `GET` of one archive file's bytes at its canonical stored path. */
export async function rawDownload(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
  fileName: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${downloadPath(name, fileName)}`, {
    headers: authHeader(credential),
  });
}

/** Raw `HEAD` of any path under the repo (P6: always `200`, existence never checked). */
export async function rawHead(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
): Promise<RawResponse> {
  return withBackoff429Response(async () => {
    const res = await fetch(`${repoUrl(repoName)}${relPath}`, {
      method: 'HEAD',
      headers: authHeader(credential),
    });
    return { status: res.status, body: Buffer.alloc(0) };
  });
}

/** One parsed `<a href="...#sha256=...">filename</a>` anchor off a project page. */
export interface ParsedSimpleLink {
  filename: string;
  href: string;
  sha256?: string;
  requiresPython?: string;
}

function unescapeHtml(value: string): string {
  return value
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

/** Parses a PEP 503 project page's anchors (`package-versions.ftl`'s exact shape: an optional
 *  `data-requires-python` attribute before the closing `>`, a `#sha256=` fragment on the href). */
export function parseSimplePage(body: Buffer): ParsedSimpleLink[] {
  const html = body.toString('utf8');
  const anchorPattern = /<a href="([^"]+)"(?:\s+data-requires-python="([^"]*)")?>([^<]+)<\/a>/g;
  const links: ParsedSimpleLink[] = [];
  for (const match of html.matchAll(anchorPattern)) {
    const href = unescapeHtml(match[1]);
    const requiresPython = match[2] !== undefined ? unescapeHtml(match[2]) : undefined;
    const filename = unescapeHtml(match[3]);
    const hashMatch = /#sha256=([^&#]+)$/.exec(href);
    links.push({
      filename,
      href,
      sha256: hashMatch?.[1],
      requiresPython,
    });
  }
  return links;
}

/** One parsed `<a href="/pypi/<repo>/simple/<name>/">...</a>` anchor off the root index (P1: the
 *  hardcoded, non-working `/pypi/` prefix). */
export interface ParsedRootLink {
  href: string;
  text: string;
}

export function parseSimpleRoot(body: Buffer): ParsedRootLink[] {
  const html = body.toString('utf8');
  const anchorPattern = /<a href="([^"]+)">([^<]+)<\/a>/g;
  const links: ParsedRootLink[] = [];
  for (const match of html.matchAll(anchorPattern)) {
    links.push({ href: unescapeHtml(match[1]), text: unescapeHtml(match[2]) });
  }
  return links;
}
