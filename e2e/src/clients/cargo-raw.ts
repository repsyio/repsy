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
 * Raw HTTP helpers for the Cargo (Rust crate registry) protocol, next to the real-client adapter
 * (`cargo.ts`), built on `raw-http.ts`, the Cargo analogue of `npm-raw.ts`. Layout/wire facts these
 * helpers rely on, read from the server source and confirmed live (see the e2e run report and
 * `README.md`'s "Scenario outcomes pinned against a running instance" for this protocol):
 *
 *  - Publish: `PUT /<repoName>/api/v1/crates/new`
 *    (`AbstractCargoPublishProtocolMethodHandler.getPathParser`, matched by the relative path
 *    ending in `/api/v1/crates/new`, not a fixed prefix). Body: a 4-byte little-endian unsigned
 *    JSON-length, the JSON metadata, a 4-byte little-endian unsigned crate-length, the `.crate`
 *    bytes (`CrateUtils.getPublishRequest`/`getCrateBytes`, `readU32LittleEndian`). Success: 200,
 *    `{"warnings":{"invalid_categories":[],"invalid_badges":[],"other":[]}}`. **Every** exception the
 *    handler's `catch (Exception e)` sees -- an invalid name/version, a too-long field, a
 *    duplicate-version `ItemAlreadyExistException` -- becomes a flat 400 with a Cargo-shaped
 *    `{"errors":[{"detail":"<exception message>"}]}` body (`CargoErrorResponse.of`); there is no
 *    403/409 on this route (`AbstractCargoPublishProtocolMethodHandler.handle`).
 *  - `AbstractCargoProtocolFacade.publish` writes the `.crate` bytes and appends the index line to
 *    storage (`AbstractCargoStorageService.writeCrateAndIndex`, `StorageStrategy.write` /
 *    `.append`) BEFORE calling `CargoCrateServiceImpl.publish`, where the duplicate-version check
 *    (`checkExistsVersion`) lives -- so a refused duplicate has already touched storage; see this
 *    file's own `README.md` section on H1 for what was observed live.
 *  - Duplicate-version rule: `CargoCrateServiceImpl.publish`/`checkExistsVersion` refuses
 *    unconditionally (`ItemAlreadyExistException("crate \`%s@%s\` already exists in this
 *    registry")`) whenever a `CargoCrateIndex` row for that exact `(crate, vers)` already exists.
 *    `allowOverride` is never read by the protocol at all (only `grep`-visible in
 *    `CargoApiFacade.java`, which just reports the panel setting) -- both `no-override` and
 *    `override` therefore share the same pinned `rejected` expectation in `catalog.ts`.
 *  - Name normalisation: `CrateUtils.normalizeCrateName` = `toLowerCase(ROOT).replace('-', '_')`.
 *    `CargoCrateServiceImpl.publish` stores the crate row under that normalised name
 *    (`crate.name`), and `createCrateIndex` sets `index.name = crate.getName()` (also normalised);
 *    `CargoCrateConverter.toCrateIndexEntry` maps the served `name` straight off `index.name`. So the
 *    sparse index serves a hyphenated crate's normalised (underscored) name, never the name it was
 *    published under -- see `registry-rules.spec.ts`'s hyphen test.
 *  - `config.json`: `GET /<repoName>/config.json`, always unauthenticated
 *    (`skipPreProcessor: true`, even on a private repo). Body `{"dl": "<base>/api/v1/crates/
 *    {crate}/{version}/download", "api": "<base>"}`, plus `"auth-required": true` iff the repo is
 *    private (`AbstractCargoConfigProtocolMethodHandler.isAuthRequired`/`getJsonConfig`). `<base>`
 *    is built from the current request's own scheme/host/port
 *    (`ServletUriComponentsBuilder.fromCurrentContextPath()`).
 *  - Sparse index: `GET /<repoName>/(1/<n>|2/<n>|3/<x>/<n>|<ab>/<cd>/<n>)`
 *    (`AbstractCargoSparseIndexProtocolMethodHandler.INDEX_PATTERN`), `permission: READ`,
 *    `skipPreProcessor: false` (so auth applies on a private repo); `/api/`, `/config.json`, `/me`
 *    are excluded by `EXCLUDED_PATTERN` so they never match this handler instead of their own.
 *    Body `text/plain`, one JSON `CrateIndexEntry` per line (`name, vers, deps, cksum, features,
 *    yanked, links, v, features2, rust_version`, `NON_NULL` so an absent optional field is omitted,
 *    not `null`). **404** both when the crate has no index entries at all AND on any exception
 *    the handler's own `catch` swallows (`AbstractCargoSparseIndexProtocolMethodHandler.handle`).
 *  - Download: `GET /<repoName>/.../api/v1/crates/<name>/<version>/download`
 *    (`AbstractCargoDownloadProtocolMethodHandler.DOWNLOAD_PATTERN`, matched by the relative path's
 *    suffix, not a fixed prefix), `permission: READ`. `application/octet-stream` on success; **404**
 *    on ANY exception (missing crate, missing version -- `AbstractCargoDownloadProtocolMethodHandler
 *    .handle`'s `catch`). The name segment is normalised before lookup
 *    (`CrateUtils.extractCrateNameAndVersion`), so a download under either the hyphenated or the
 *    normalised spelling resolves to the same stored crate.
 *  - Auth header: `CargoAuthPreProcessor.normalizeAuthHeader` treats any `Authorization` value that
 *    does not already start with `Basic `/`Bearer ` as a raw Cargo token and prefixes it with
 *    `Bearer ` before dispatching to `handleBearerAuth` -- matching the real Cargo CLI, which "sends
 *    the token as a raw value with no prefix" (this file's own header comment on the pre-processor).
 *    A `Basic <base64>` value is dispatched to `handleBasicAuth` unchanged.
 *  - Yank/unyank (step 5b): `DELETE`/`PUT /<repoName>/api/v1/crates/<name>/<version>/(yank|unyank)`
 *    (`AbstractCargoYankProtocolMethodHandler`), `permission: WRITE`. Success: 200 `{"ok":true}`.
 *    Confirmed live with the real `cargo yank`/`cargo yank --undo` binaries: both exit 0 and the
 *    served sparse index entry's own `yanked` field flips accordingly; a yanked version's `.crate` is
 *    still downloadable afterwards (`AbstractCargoDownloadProtocolMethodHandler` has no yanked check
 *    at all) -- Cargo's yank semantics are index-only (a yanked version is excluded from fresh
 *    dependency RESOLUTION, never from download of an already-pinned one), which is exactly what was
 *    observed. A read-only deploy token is refused with a plain 401 `unAuthorized`, confirmed live
 *    (the WRITE permission is enforced, not merely documented).
 *  - Search: `GET /<repoName>/api/v1/crates?q=<query>` (`AbstractCargoSearchProtocolMethodHandler`),
 *    `permission: READ`. Success: 200 `{"crates":[...],"meta":{"total":N}}`. Confirmed live with the
 *    real `cargo search --registry repsy` binary: exit 0, stdout lists the crate.
 *  - Owners: `GET/PUT/DELETE /<repoName>/api/v1/crates/<name>/owners`
 *    (`CargoOwnersProtocolMethodHandler`, defined directly in `repsy-backend`, NOT the shared
 *    `repsy-protocols/cargo` abstract-class family every other cargo route extends) -- confirmed
 *    live: it answers every one of those three methods identically, with a FIXED body,
 *    `{"ok":true,"msg":"Ownership is managed at the repository level in this registry"}`, and
 *    `permission: WRITE` even for the GET. There is no `users` array at all. A real `cargo owner
 *    --list --registry repsy <crate>` therefore FAILS client-side (confirmed live, exit 101: "missing
 *    field `users` at line 1 column 81") even though the raw HTTP GET itself answers 200 -- see the
 *    candidate-bug test in `tests/cargo/protocol-specific.spec.ts`.
 */
import { repoUrl as repositoryUrl } from '../repo-url.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import {
  adminCredential,
  msgIdOf,
  type RawResponse,
  sha256Hex,
  withBackoff429Response,
} from './raw-http.js';

export { adminCredential, sha256Hex, type RawResponse };

/**
 * The `Authorization` value a real `cargo` client would send for `credential`, exactly as it hits
 * the wire (see this file's header): a `token`-kind credential (a deploy token, what
 * `CARGO_REGISTRIES_REPSY_TOKEN` carries) is sent RAW, no `Bearer` prefix -- the server adds that
 * itself. A `password`-kind credential is sent as `Basic <base64(user:pass)>`, which `cargo`'s own
 * `check_token` accepts (RFC 9110 field-value ASCII, spaces included) and which
 * `CargoAuthPreProcessor.normalizeAuthHeader` leaves untouched since it already starts with
 * `Basic `. No credential (`anonymous`) sends no header, matching a real `cargo` invocation with no
 * configured token for the registry.
 */
export function cargoAuthHeader(credential: MaterializedCredential): Record<string, string> {
  if (credential.transport !== 'basic') {
    return {};
  }
  if (credential.kind === 'token') {
    return { Authorization: credential.password ?? '' };
  }
  const basic = Buffer.from(`${credential.username ?? ''}:${credential.password ?? ''}`).toString(
    'base64',
  );
  return { Authorization: `Basic ${basic}` };
}

/**
 * The literal value `CARGO_REGISTRIES_REPSY_TOKEN` is set to for `credential`; `undefined` for
 * `anonymous` (the env var is then left unset entirely, so `cargo` sends no `Authorization` header
 * at all). Cargo's `cargo:token` credential provider sends this env var's value VERBATIM as the
 * `Authorization` header (`registry-authentication.html`), so this is exactly `cargoAuthHeader`'s
 * `Authorization` value -- deliberately the same function, not a re-derivation, so the configured
 * token and the raw probe's header can never drift apart.
 */
export function cargoToken(credential: MaterializedCredential): string | undefined {
  return cargoAuthHeader(credential).Authorization;
}

function repoUrl(repoName: string): string {
  return repositoryUrl(repoName, '');
}

/**
 * `e2e_<runid>_<slugified scenario id, hyphens turned to underscores>` -- matches
 * `ProtocolAdapter.packageName`'s signature so `cargoAdapter.packageName` can be this function
 * directly. Underscores only, deliberately NOT `slugify`'s hyphenated output: the served sparse
 * index always normalises `-` to `_` (this file's header), so a hyphenated name would round-trip
 * through the catalog loop's `cargo fetch` under a different spelling than it was published with --
 * see `cargo.ts`'s file header and the dedicated hyphen tests in `tests/cargo/*.spec.ts`.
 */
export function crateName(runId: string, scenario: Scenario): string {
  return `e2e_${runId}_${scenario.id.replace(/[^a-z0-9]/gi, '_').toLowerCase()}`;
}

/** The index-file "bucket" segment layout (`AbstractCargoStorageService.getIndexPath`, mirrored by
 *  `AbstractCargoSparseIndexProtocolMethodHandler.INDEX_PATTERN`): 1 char -> `1/<n>`, 2 -> `2/<n>`,
 *  3 -> `3/<first>/<n>`, 4+ -> `<first two>/<next two>/<n>`. */
export function indexPath(name: string): string {
  const len = name.length;
  if (len === 1) {
    return `1/${name}`;
  }
  if (len === 2) {
    return `2/${name}`;
  }
  if (len === 3) {
    return `3/${name.slice(0, 1)}/${name}`;
  }
  return `${name.slice(0, 2)}/${name.slice(2, 4)}/${name}`;
}

export function crateFileName(name: string, version: string): string {
  return `${name}-${version}.crate`;
}

/** The canonical, repo-relative download path Cargo's own `config.json` `dl` template resolves to. */
export function downloadPath(name: string, version: string): string {
  return `api/v1/crates/${name}/${version}/download`;
}

/** `DELETE .../<name>/<version>/yank` (step 5b): confirmed live to answer 200 `{"ok":true}` for a
 *  WRITE-permitted credential, matching `AbstractCargoYankProtocolMethodHandler`'s header. */
export function yankUrl(name: string, version: string): string {
  return `api/v1/crates/${name}/${version}/yank`;
}

/** `PUT .../<name>/<version>/unyank` (step 5b): same handler/response shape as `yankUrl`, the
 *  `isYank` branch just flips (`AbstractCargoYankProtocolMethodHandler.handle`). */
export function unyankUrl(name: string, version: string): string {
  return `api/v1/crates/${name}/${version}/unyank`;
}

/** `GET .../api/v1/crates?q=<query>` (step 5b): `{"crates": [...], "meta": {"total": N}}`
 *  (`AbstractCargoSearchProtocolMethodHandler.handle`). */
export function searchUrl(query: string): string {
  return `api/v1/crates?q=${encodeURIComponent(query)}`;
}

/** `GET .../<name>/owners` (step 5b): see this file's header -- Repsy OS's own
 *  `CargoOwnersProtocolMethodHandler` (never the shared `repsy-protocols/cargo` abstract class)
 *  answers this route with a FIXED `{"ok":true,"msg":"..."}` body, no `users` array at all, for
 *  every one of GET/PUT/DELETE. */
export function ownersUrl(name: string): string {
  return `api/v1/crates/${name}/owners`;
}

async function rawRequest(
  url: string,
  init: { method?: string; headers?: Record<string, string>; body?: Buffer },
): Promise<RawResponse> {
  return withBackoff429Response(async () => {
    const res = await fetch(url, init as RequestInit);
    const bytes = Buffer.from(await res.arrayBuffer());
    return { status: res.status, msgId: msgIdOf(bytes), body: bytes };
  });
}

/**
 * Builds the exact byte layout a real `cargo publish` PUTs (this file's header, `CrateUtils
 * .getPublishRequest`/`getCrateBytes`): a 4-byte LE JSON length, the JSON metadata, a 4-byte LE
 * crate length, the `.crate` bytes. Every field `CratePublishRequest` reads is included (even when
 * empty/null) the same way a real client's normalized manifest almost always sends them, so a
 * missing-field code path is never what a probe accidentally exercises instead of the one it names.
 */
export function buildPublishBody(opts: {
  name: string;
  version: string;
  crateBytes: Buffer;
  description?: string;
  license?: string;
  /** README text the panel renders on the version detail (RPS-1257); default none. */
  readme?: string;
  /** Dependencies in the registry-API shape (`name`, `version_req`, `kind`, ...); default none. */
  deps?: readonly Record<string, unknown>[];
}): Buffer {
  const metadata = {
    name: opts.name,
    vers: opts.version,
    deps: [...(opts.deps ?? [])] as unknown[],
    features: {} as Record<string, unknown>,
    authors: [] as string[],
    description: opts.description ?? `e2e ${opts.name}@${opts.version}`,
    documentation: null,
    homepage: null,
    readme: opts.readme ?? null,
    readme_file: null,
    keywords: [] as string[],
    categories: [] as string[],
    license: opts.license ?? 'MIT',
    license_file: null,
    repository: null,
    badges: {} as Record<string, unknown>,
    links: null,
  };
  const jsonBytes = Buffer.from(JSON.stringify(metadata), 'utf8');

  const jsonLen = Buffer.alloc(4);
  jsonLen.writeUInt32LE(jsonBytes.length, 0);
  const crateLen = Buffer.alloc(4);
  crateLen.writeUInt32LE(opts.crateBytes.length, 0);

  return Buffer.concat([jsonLen, jsonBytes, crateLen, opts.crateBytes]);
}

/** Raw `PUT` of a publish body, exactly what `cargo publish` sends (`Content-Type:
 *  application/octet-stream`, `Accept: application/json` -- `crates-io/lib.rs`). */
export async function rawPublish(
  repoName: string,
  credential: MaterializedCredential,
  body: Buffer,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}api/v1/crates/new`, {
    method: 'PUT',
    headers: {
      ...cargoAuthHeader(credential),
      'Content-Type': 'application/octet-stream',
      Accept: 'application/json',
    },
    body,
  });
}

/** Raw, always-unauthenticated `GET` of `config.json` (§1: `skipPreProcessor: true`). */
export async function rawGetConfigJson(repoName: string): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}config.json`, {});
}

/** Raw `GET` of the sparse index for one crate name, with `credential`'s auth header. */
export async function rawGetIndex(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${indexPath(name)}`, {
    headers: cargoAuthHeader(credential),
  });
}

/**
 * `GET .../me` with `credential` (Cargo's login: a password or a deploy token in, a token out; a
 * token in, a renewed one out). Answers the token the client keeps when it is `200`.
 */
export async function rawMe(
  repoName: string,
  credential: MaterializedCredential,
): Promise<{ status: number; token?: string; response: RawResponse }> {
  const response = await rawRequest(`${repoUrl(repoName)}me`, {
    headers: cargoAuthHeader(credential),
  });
  let token: string | undefined;
  if (response.status === 200) {
    const parsed = JSON.parse(response.body.toString('utf8')) as { token?: unknown };
    token = typeof parsed.token === 'string' ? parsed.token : undefined;
  }
  return { status: response.status, token, response };
}

/** Raw `GET` of a crate's sparse-index entry with a login token as a `Bearer` value (RPS-1552). */
export async function rawGetIndexWithBearer(
  repoName: string,
  name: string,
  token: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${indexPath(name)}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

/** Raw `GET` of a crate's `.crate` bytes at its canonical download path. */
export async function rawDownload(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
  version: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${downloadPath(name, version)}`, {
    headers: cargoAuthHeader(credential),
  });
}

/** Raw `DELETE` yank of one crate version (step 5b), `permission: WRITE`. */
export async function rawYank(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
  version: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${yankUrl(name, version)}`, {
    method: 'DELETE',
    headers: cargoAuthHeader(credential),
  });
}

/** Raw `PUT` unyank of one crate version (step 5b), `permission: WRITE`. */
export async function rawUnyank(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
  version: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${unyankUrl(name, version)}`, {
    method: 'PUT',
    headers: cargoAuthHeader(credential),
  });
}

/** Raw `GET` crate search (step 5b), `permission: READ`. */
export async function rawSearch(
  repoName: string,
  credential: MaterializedCredential,
  query: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${searchUrl(query)}`, {
    headers: cargoAuthHeader(credential),
  });
}

/** Raw `GET` crate search with paging parameters (RPS-1486): `cargo search --limit N` sends
 *  `per_page=N`; `page` is the 1-based page. Values go on the URL as given, so a test can send garbage. */
export async function rawSearchPage(
  repoName: string,
  credential: MaterializedCredential,
  query: string,
  paging: { perPage?: string; page?: string },
): Promise<RawResponse> {
  const extra =
    (paging.perPage !== undefined ? `&per_page=${paging.perPage}` : '') +
    (paging.page !== undefined ? `&page=${paging.page}` : '');
  return rawRequest(`${repoUrl(repoName)}${searchUrl(query)}${extra}`, {
    headers: cargoAuthHeader(credential),
  });
}

/** Raw `GET` of one crate's owners (step 5b), `permission: WRITE` on Repsy OS's own handler
 *  (`CargoOwnersProtocolMethodHandler` -- see this file's header, distinct from a real crates.io
 *  registry's owners route, which is READ-only for a GET). */
export async function rawOwners(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${ownersUrl(name)}`, {
    headers: cargoAuthHeader(credential),
  });
}

/** Parses the search envelope's body (step 5b): `{"crates": [...], "meta": {"total": N}}`. */
export interface ParsedSearchResult {
  crates: unknown[];
  meta: { total: number };
}

export function parseSearch(body: Buffer): ParsedSearchResult {
  return JSON.parse(body.toString('utf8')) as ParsedSearchResult;
}

/** One line of the sparse index's `text/plain` body, parsed (`CrateIndexEntry`'s JSON shape). */
export interface ParsedIndexEntry {
  name: string;
  vers: string;
  cksum: string;
  yanked: boolean;
  [key: string]: unknown;
}

/** Parses the sparse index's newline-delimited JSON body into one entry per line. */
export function parseIndex(body: Buffer): ParsedIndexEntry[] {
  return body
    .toString('utf8')
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .map((line) => JSON.parse(line) as ParsedIndexEntry);
}

/** `{"errors":[{"detail":"..."}]}` -- Cargo's own error envelope (distinct from Repsy's `msgId`
 *  envelope, which this protocol's publish/config routes never use: every exception becomes this
 *  shape instead, see this file's header). `undefined` when the body is not that shape. */
export function cargoErrorDetail(body: Buffer): string | undefined {
  try {
    const parsed = JSON.parse(body.toString('utf8')) as {
      errors?: { detail?: unknown }[];
    };
    const detail = parsed.errors?.[0]?.detail;
    return typeof detail === 'string' ? detail : undefined;
  } catch {
    return undefined;
  }
}
