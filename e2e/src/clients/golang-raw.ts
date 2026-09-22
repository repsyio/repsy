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
 * Raw HTTP helpers for the Go module proxy protocol, next to the real-client adapter (`golang.ts`),
 * built on `raw-http.ts`, the Go analogue of `nuget-raw.ts`/`cargo-raw.ts`. Every wire fact below was
 * read from the server source (`repsy-protocols/golang/**`, `repsy-backend/.../protocols/golang/**`)
 * and then confirmed live against a running instance (see `README.md`'s "Go runner" section for the
 * raw evidence and the H/G numbers this file's comments reference).
 *
 *  - Repsy is a PUSH registry: `PUT /<repoName>/<modulePath>/@v/<version>[.zip|.mod|.info]` (any of
 *    the three suffixes, or none at all -- `GoVersionUtils.extractVersionFromPath` strips a known
 *    extension off the last path segment and nothing else looks at it, so `.../@v/v1.0.0`,
 *    `.../@v/v1.0.0.zip` and even `.../@v/v1.0.0.mod`/`.info` ALL mean "store the zip body as version
 *    v1.0.0" -- confirmed live/G6, `AbstractGoUploadProtocolMethodHandler`, `permission: WRITE`).
 *    `Content-Sha256` is an optional request header, lower-cased before comparison, checked against
 *    the WHOLE request body (confirmed live/R3). Success: `200`, empty body (never `201`, confirmed
 *    live) -- there is deliberately no re-PUT/re-probe pattern here the way pypi's/nuget's adapters
 *    use: a real `curl -T` already exposes the raw HTTP status via `-w '%{http_code}'`, so `golang.ts`
 *    needs no companion raw request the way a client that hides the status behind its own exit code
 *    does.
 *  - Upload pipeline order (`AbstractGoProtocolFacade.upload`): `extractModulePath` (needs `/@v/` at
 *    index > 1, else `400 invalidModulePath`) -> `decodeModulePath` (Go's `!x` -> `X` escape) ->
 *    lower-case (the stored/DB path -- module paths are case-INSENSITIVE on Repsy OS even though Go
 *    itself treats them as case-sensitive, confirmed live/G10: `GoProbe` and `goprobe` collide) ->
 *    length checks (`400 modulePathTooLong`/`moduleVersionTooLong`, RPS-1072) -> `Content-Sha256`
 *    check -> `go.mod` extracted from the zip at the EXACT entry name `<decodedPath>@<version>/go.mod`
 *    (case-preserved, not lower-cased -- a missing entry is `400 goModNotFoundInZip`, confirmed live
 *    for a non-zip body too) -> `GoModFileValidator` (empty -> `400 goModFileEmpty`; no `^module\s+\S+`
 *    line -> `400 goModMissingModuleDirective`; first path segment without a `.` -> `400
 *    goModInvalidModulePath`; **the directive's value is never compared against the URL's own module
 *    path**, confirmed live/G2: a go.mod naming a completely different module still uploads) -> **no
 *    version-string validation of ANY kind** (confirmed live/G1: `banana`, `v1`, `1.0.0` are all
 *    accepted, stored, and listed) -> the DB duplicate-version check (`ItemAlreadyExistException
 *    ("goModuleVersionAlreadyExists")` -> `409`, confirmed live/H9 -- unconditional: `allowOverride`
 *    is never read anywhere in either Go package, grep-confirmed, so `no-override` AND `override` both
 *    pin `409` in `catalog.ts`) -> only then the THREE storage writes (`.mod`, `.zip` verbatim, then a
 *    generated `.info` -- `{"Version":"<version>","Time":"<ISO-8601>"}`). The duplicate check running
 *    BEFORE any storage write means a refused duplicate cannot corrupt storage (confirmed live/H9: the
 *    original `.mod`/`.zip` bytes are unchanged after a refused re-PUT) -- the INVERSE of cargo's
 *    RPS-1124 storage-before-DB bug; here the DB row commits before the storage writes instead (G4,
 *    not independently observable over the wire, noted for the coordinator to consider commenting on
 *    the existing RPS-1124 storage/DB-ordering audit story, whose title already names Go).
 *  - Deleting a version (`DELETE /api/go/modules/<repo>/versions?modulePath=&version=`, panel API,
 *    MANAGE) removes BOTH the DB row and the three storage files outright; a re-upload of the exact
 *    same version afterwards succeeds with a fresh `200`, never a `410` (confirmed live/G8: the
 *    `GoVersionGoneException`/410 path exists in `AbstractGoDownloadProtocolMethodHandler` but is
 *    grep-confirmed dead -- nothing in either Go package ever throws it; the `deleted` column
 *    `V0002__Golang_Protocol.sql` created has no entity field reading it).
 *  - `@v/list`: lists the storage directory `/<modulePath>/@v/`, keeps `*.info` names, strips the
 *    extension, sorts with `GoVersionUtils.COMPARATOR` (real semver precedence; a non-semver string --
 *    `banana` -- falls back to `String.compareTo` against ANY other operand, even a real semver one,
 *    confirmed live), joins with `\n`, `text/plain`. An unknown module is `200` with an EMPTY body
 *    (`listDirectory` swallows `ItemNotFoundException`), never a `404`.
 *  - `@latest`: reads the DB (`findLatestPublishedVersion`, `max()` under the same `COMPARATOR`) --
 *    a DIFFERENT source of truth than `@v/list`'s storage directory listing (G5, architectural: the
 *    two routes call `goStorageService.listDirectory` and `goModuleService.findLatestPublishedVersion`
 *    respectively, confirmed from source, not independently forced live in this harness). Unknown
 *    module -> `404`; found -> serves that version's `.info` FILE, `application/json`.
 *  - Plain `GET .../@v/<version>.mod|.info|.zip` serves the literal stored path (`text/plain`/
 *    `application/json`/`application/octet-stream`); missing -> `404`.
 *  - `sumdb/supported`: `404` on BOTH the API port (`GolangModuleController.checkSumdbSupported`,
 *    deliberate) and the protocol port (the `go` command's own `GET <GOPROXY>/sumdb/supported` lands
 *    on the download handler, which 404s by a plain storage miss -- confirmed live/G9, the same
 *    observed signal on both ports by accident, not by any shared code path).
 *  - `HEAD` on ANY path (an existing `.info` included) is `404` -- confirmed live/H17: no
 *    `ProtocolMethodHandler` in either Go package lists `HttpMethod.HEAD` among its supported methods
 *    (only the download handler's `GET` and the upload handler's `PUT`), so the router itself has
 *    nothing to dispatch a `HEAD` to. This is the OPPOSITE of pypi's own `HEAD`-always-`200` quirk
 *    (`pypi-raw.ts`'s file header, P6): Go's router refuses a method it has no handler for, PyPI's
 *    root-level early-return does not check existence at all.
 *  - Auth (`GolangAuthPreProcessor`, priority 100): skipped only for a public-repo READ. Otherwise a
 *    missing/unparseable `Authorization` is a bodyless `401` + `WWW-Authenticate: Basic
 *    realm="Repsy Go Module Proxy"` (confirmed live). `GolangAuthComponent` is a bare
 *    `ProtocolAuthService` subclass with no overrides: `handleBasicAuth` tries the PASSWORD as a
 *    deploy token FIRST, username ignored for a token credential, then falls back to username/password
 *    -- so a read-only deploy token attempting a WRITE is the same flat `401` every other protocol in
 *    this harness gives (confirmed live), never a `403`. Single-hop Basic (or Bearer, which no
 *    supported Go client ever sends), no token exchange.
 *  - `releases`/`snapshots`/`allowOverride` are never read by any Go code (grep-confirmed: no
 *    `allowOverride|isReleases|isSnapshots` hit under either Go package) -- `catalog.ts` never adds
 *    `golang` to the maven/nuget-only `releases`/`snapshots` scenarios, same as docker/helm/pypi.
 */
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { zipSync } from 'fflate';
import mustache from 'mustache';

import { env } from '../env.js';
import { boundedSemverVersion, slugify } from '../scenarios/coordinates.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import { withBackoff429 } from '../scenarios/remote-throttle.js';
import {
  adminCredential,
  authHeader,
  msgIdOf,
  type RawResponse,
  sha256Hex,
  withBackoff429Response,
} from './raw-http.js';

export {
  adminCredential,
  authHeader,
  msgIdOf,
  sha256Hex,
  withBackoff429Response,
  type RawResponse,
};

const __dirname = path.dirname(fileURLToPath(import.meta.url));
/** Exported so `golang.ts` can render the (publish-unrelated) consumer templates from the same
 *  directory without re-deriving the path. */
export const TEMPLATES_DIR = path.resolve(__dirname, '../packages/golang');

/**
 * The module-path domain every fixture module lives under. `.test` is IANA-reserved for testing
 * (RFC 6761) and therefore never resolves and never answers `GET <domain>?go-get=1` with `200` --
 * `repsy-protocols/golang/README.md`'s "Module Path Convention" names this exact property as the
 * rule a safe domain must satisfy, so a real `go` command never attempts VCS discovery for it even
 * though `GOPROXY` is the only source configured (confirmed live/H1-H2/H6: `.test` is never dialed).
 */
export const MODULE_DOMAIN = 'e2e.repsy.test';

/** `e2e.repsy.test/e2e-<runid>-<slugified scenario id>` -- the FULL module path, matching
 *  `ProtocolAdapter.packageName`'s signature (unlike most other protocols, a Go "package name" IS a
 *  module path; there is no separate `modulePath()` step). Every character `scenario.id`/`runId` can
 *  contain is already alnum/`-`, so this never needs Go's `!`-escape encoding for a normal scenario --
 *  only the dedicated mixed-case test (`registry-rules.spec.ts`) deliberately uppercases part of it. */
export function packageName(runId: string, scenario: Scenario): string {
  return `${MODULE_DOMAIN}/e2e-${runId}-${slugify(scenario.id)}`;
}

/** `v0.<seconds since VERSION_EPOCH>.<seq>` -- `boundedSemverVersion()` (`coordinates.ts`) with a
 *  leading `v`, which is ALL Go's own semver grammar (`v(\d+)\.(\d+)\.(\d+)(-...)?(\+...)?`) and
 *  Repsy's upload path require (confirmed live/H16: accepted by both, and `@latest` picks it when it
 *  is the sole version of a module). `versionType` is ignored: Go has no release/snapshot repo-setting
 *  distinction (`golang.ts`'s `adapter.version`). */
export function goVersion(): string {
  return `v${boundedSemverVersion()}`;
}

/** Go's module-path escape encoding (`golang.org/x/mod/module.EscapePath`): every uppercase letter
 *  becomes `!` + its lower-case form, and a literal `!` becomes `!!` -- the exact inverse of
 *  `GoVersionUtils.decodeModulePath`. Only needed to build the URL a REAL `go` command would send for
 *  a mixed-case module path (`registry-rules.spec.ts`'s H18/G10 test); a raw HTTP probe from this
 *  harness can send the literal uppercase characters instead, since the server only un-escapes `!`
 *  sequences and then lower-cases everything regardless (confirmed live). */
export function escapeModulePath(modulePath: string): string {
  let out = '';
  for (const ch of modulePath) {
    if (ch === '!') {
      out += '!!';
    } else if (ch >= 'A' && ch <= 'Z') {
      out += `!${ch.toLowerCase()}`;
    } else {
      out += ch;
    }
  }
  return out;
}

export function moduleBaseUrl(repoName: string, modulePath: string): string {
  return `${env.repoBaseUrl}/${repoName}/${modulePath}`;
}

export function listRelPath(modulePath: string): string {
  return `${modulePath}/@v/list`;
}

export function latestRelPath(modulePath: string): string {
  return `${modulePath}/@latest`;
}

export function infoRelPath(modulePath: string, version: string): string {
  return `${modulePath}/@v/${version}.info`;
}

export function modRelPath(modulePath: string, version: string): string {
  return `${modulePath}/@v/${version}.mod`;
}

export function zipRelPath(modulePath: string, version: string): string {
  return `${modulePath}/@v/${version}.zip`;
}

/** The URL a real `curl -T` publish is given (`.zip` suffix, matching the panel's OWN documented
 *  incantation, `golang-config.component.ts`) -- `uploadRelPath(..., { suffix: '' })`/`'.mod'`/
 *  `'.info'` exercise R2/G6 (the backend README's own suffix-less spelling, and the "any suffix means
 *  zip" quirk) from `registry-rules.spec.ts`. */
export function uploadRelPath(
  modulePath: string,
  version: string,
  opts?: { suffix?: '.zip' | '.mod' | '.info' | '' },
): string {
  const suffix = opts?.suffix ?? '.zip';
  return `${modulePath}/@v/${version}${suffix}`;
}

export function uploadUrl(
  repoName: string,
  modulePath: string,
  version: string,
  opts?: { suffix?: '.zip' | '.mod' | '.info' | '' },
): string {
  return `${env.repoBaseUrl}/${repoName}/${uploadRelPath(modulePath, version, opts)}`;
}

/** One `<hex sha256>  <name>\n` line of a real `golang.org/x/mod/sumdb/dirhash.Hash1` computation --
 *  TWO spaces, the sha256 in lower-case hex, no `h1:`/base64 on the INNER line. Confirmed live/H2:
 *  reproduces byte-for-byte what a real `go mod download -json` reports as `Sum`/`GoModSum` for a
 *  module this harness published (see `README.md`'s "Go runner" section for the exact values). Entry
 *  names are sorted lexicographically BEFORE the lines are built (not the built lines themselves,
 *  which would sort differently since the hex digest comes first on each line) -- the mistake
 *  `GoModuleHashCalculator` on the backend makes (G3, `"name:hex\n"`, colon-separated, name first,
 *  never sorted by name alone): the stored `h1:` hashes it computes are NOT dirhash-compatible, but
 *  that mismatch is not observable over the wire (`GoModuleVersionListItem` exposes no hash field, and
 *  the go command never reads Repsy's stored hash at all, only computes its own from the bytes it
 *  downloaded) -- noted here as an observation from source for the coordinator's report, not asserted
 *  by any test in this harness. */
export function dirhashHash1(entries: readonly { name: string; content: Buffer }[]): string {
  const sorted = [...entries].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  const lines = sorted.map((entry) => `${sha256Hex(entry.content)}  ${entry.name}\n`);
  const outer = createHash('sha256').update(lines.join(''), 'utf8').digest('base64');
  return `h1:${outer}`;
}

/** Renders `go.template.mod` for `modulePath` alone (no marker involved) -- shared by `buildModuleZip`
 *  and by `golang.ts`'s `afterSuccessfulRoundTrip`, which needs the EXACT expected `go.mod` text
 *  without holding on to the `BuiltGoModule` a scenario's own (possibly refused) publish produced. */
export async function renderGoModText(modulePath: string): Promise<string> {
  const template = await fs.readFile(path.join(TEMPLATES_DIR, 'go.template.mod'), 'utf8');
  return mustache.render(template, { modulePath });
}

export interface BuiltGoModule {
  modulePath: string;
  version: string;
  marker: string;
  /** The full module zip, entries prefixed `<modulePath>@<version>/`, no directory entries (confirmed
   *  live/H12: `fflate.zipSync` with no directory entries passes both Go's own zip-entry-prefix check
   *  and its unzip check). */
  bytes: Buffer;
  sha256Hex: string;
  /** The `go.mod` bytes alone, exactly as they appear inside `bytes` -- what the server extracts and
   *  stores as `<version>.mod`, and what a real `go mod download -json`'s `GoModSum` hashes. */
  goMod: Buffer;
  /** `dirhash.Hash1` of the whole zip -- what a real `go mod download -json` reports as `Sum`. */
  h1Zip: string;
  /** `dirhash.HashGoMod` of `goMod` alone -- what a real `go mod download -json` reports as
   *  `GoModSum`. */
  h1Mod: string;
  entryNames: string[];
}

/** Hand-builds a Go module zip with `fflate.zipSync` (never `go mod`/`go build` -- there is no
 *  official Go publisher at all, see this story's own constraints and `golang.ts`'s file header):
 *  `go.mod` (rendered from `go.template.mod`), `hello.go` (`hello.template.go`, a `Marker` constant)
 *  and a plain-text `e2e-marker.txt` carrying the same marker, every entry prefixed
 *  `<modulePath>@<version>/` with NO directory entries (confirmed live/H12/H1). */
export async function buildModuleZip(opts: {
  modulePath: string;
  version: string;
  marker?: string;
}): Promise<BuiltGoModule> {
  const marker = opts.marker ?? randomUUID();
  const prefix = `${opts.modulePath}@${opts.version}/`;

  const helloTemplate = await fs.readFile(path.join(TEMPLATES_DIR, 'hello.template.go'), 'utf8');

  const goModText = await renderGoModText(opts.modulePath);
  const helloText = mustache.render(helloTemplate, { marker });
  const markerText = `${marker}\n`;

  const entries: Record<string, Uint8Array> = {
    [`${prefix}go.mod`]: new TextEncoder().encode(goModText),
    [`${prefix}hello.go`]: new TextEncoder().encode(helloText),
    [`${prefix}e2e-marker.txt`]: new TextEncoder().encode(markerText),
  };

  const bytes = Buffer.from(zipSync(entries, { level: 0 }));
  const goMod = Buffer.from(goModText, 'utf8');
  const entryNames = Object.keys(entries);

  const h1Zip = dirhashHash1(
    entryNames.map((name) => ({ name, content: Buffer.from(entries[name]) })),
  );
  const h1Mod = dirhashHash1([{ name: 'go.mod', content: goMod }]);

  return {
    modulePath: opts.modulePath,
    version: opts.version,
    marker,
    bytes,
    sha256Hex: sha256Hex(bytes),
    goMod,
    h1Zip,
    h1Mod,
    entryNames,
  };
}

/** Like `buildModuleZip`, but with an explicit, arbitrary `go.mod` TEXT instead of the rendered
 *  template -- for a test that deliberately builds a MISMATCHED or otherwise non-conforming go.mod
 *  (`registry-rules.spec.ts`'s G2 test). The zip shape (entry prefix, no directory entries) is
 *  otherwise identical to `buildModuleZip`'s. */
export function buildModuleZipWithGoMod(opts: {
  modulePath: string;
  version: string;
  goModText: string;
}): { bytes: Buffer; sha256Hex: string } {
  const prefix = `${opts.modulePath}@${opts.version}/`;
  const entries: Record<string, Uint8Array> = {
    [`${prefix}go.mod`]: new TextEncoder().encode(opts.goModText),
    [`${prefix}hello.go`]: new TextEncoder().encode('package e2e\n'),
  };
  const bytes = Buffer.from(zipSync(entries, { level: 0 }));
  return { bytes, sha256Hex: sha256Hex(bytes) };
}

export interface GoRawResponse extends RawResponse {
  contentType?: string;
}

async function rawFetch(
  url: string,
  init: RequestInit,
): Promise<RawResponse & { headers: Headers }> {
  let last: (RawResponse & { headers: Headers }) | undefined;
  await withBackoff429(async () => {
    const res = await fetch(url, init);
    const bytes = Buffer.from(await res.arrayBuffer());
    last = { status: res.status, msgId: msgIdOf(bytes), body: bytes, headers: res.headers };
    return last.status;
  });
  return last as RawResponse & { headers: Headers };
}

function toGoResponse(res: RawResponse & { headers: Headers }): GoRawResponse {
  return {
    status: res.status,
    body: res.body,
    msgId: res.msgId,
    contentType: res.headers.get('content-type') ?? undefined,
  };
}

/** Raw `PUT` of arbitrary bytes at an arbitrary path under the repo -- the primitive every other raw
 *  upload in this file (and every zip-validation/malformed-path test in `registry-rules.spec.ts`)
 *  builds on. */
export async function rawPut(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
  bytes: Buffer,
  opts?: { contentSha256?: string; contentType?: string },
): Promise<GoRawResponse> {
  const headers: Record<string, string> = { ...authHeader(credential) };
  if (opts?.contentSha256 !== undefined) {
    headers['Content-Sha256'] = opts.contentSha256;
  }
  if (opts?.contentType !== undefined) {
    headers['Content-Type'] = opts.contentType;
  }
  const res = await rawFetch(`${env.repoBaseUrl}/${repoName}/${relPath}`, {
    method: 'PUT',
    headers,
    body: new Uint8Array(bytes),
  });
  return toGoResponse(res);
}

/** Raw `PUT` of a built module zip, exactly what a real `curl -T` publish sends (`golang.ts`'s file
 *  header): `opts.contentSha256` defaults to the CORRECT sha256 of `built.bytes` when omitted (pass an
 *  explicit wrong/absent value to exercise R3). */
export async function rawUpload(
  repoName: string,
  credential: MaterializedCredential,
  built: BuiltGoModule,
  opts?: {
    contentSha256?: string | null;
    contentType?: string;
    urlSuffix?: '.zip' | '.mod' | '.info' | '';
    bytesOverride?: Buffer;
  },
): Promise<GoRawResponse> {
  const contentSha256 =
    opts?.contentSha256 === null ? undefined : (opts?.contentSha256 ?? built.sha256Hex);
  return rawPut(
    repoName,
    credential,
    uploadRelPath(built.modulePath, built.version, { suffix: opts?.urlSuffix }),
    opts?.bytesOverride ?? built.bytes,
    { contentSha256, contentType: opts?.contentType },
  );
}

export async function rawGet(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
): Promise<GoRawResponse> {
  const res = await rawFetch(`${env.repoBaseUrl}/${repoName}/${relPath}`, {
    headers: authHeader(credential),
  });
  return toGoResponse(res);
}

/** Raw `HEAD` of any path (H17: always `404`, confirmed live -- neither protocol method handler lists
 *  `HEAD` among its supported methods). */
export async function rawHead(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
): Promise<GoRawResponse> {
  const res = await rawFetch(`${env.repoBaseUrl}/${repoName}/${relPath}`, {
    method: 'HEAD',
    headers: authHeader(credential),
  });
  return toGoResponse(res);
}

/** `@v/list`'s `text/plain` body -> the version strings it lists, in the order the server sent them
 *  (already `GoVersionUtils.COMPARATOR`-sorted). An empty/unknown-module body is `[]`. */
export function parseVersionList(body: Buffer): string[] {
  const text = body.toString('utf8');
  return text.length === 0 ? [] : text.split('\n').filter((line) => line.length > 0);
}

export interface ParsedGoInfo {
  Version: string;
  Time: string;
}

export function parseInfo(body: Buffer): ParsedGoInfo {
  return JSON.parse(body.toString('utf8')) as ParsedGoInfo;
}
