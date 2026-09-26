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
 * Raw HTTP helpers for the NuGet (.NET package registry) protocol, next to the real-client adapter
 * (`nuget.ts`), built on `raw-http.ts`, the NuGet analogue of `cargo-raw.ts`. Layout/wire facts these
 * helpers rely on, read from the server source and confirmed live (see `README.md`'s "NuGet runner"
 * section for the raw evidence this step's own H1-H13 probes produced):
 *
 *  - Publish: `PUT /<repoName>/v3/package/` (a TRAILING slash -- the real client's
 *    `PackageUpdateResource.GetServiceEndpointUrl` always appends one, and the server strips exactly
 *    one before matching, `AbstractNuGetPublishProtocolMethodHandler.getPathParser` L69-83). Body:
 *    `multipart/form-data` whose part is named `package` (case-insensitive) or, failing that, the
 *    first part (L113-129). Success: `201 Created`, empty body. Every exception the handler's own
 *    `catch` sees becomes a Repsy-shaped `{"errors":[{"message":"<msg>"}]}` (`NuGetErrorResponse.of`),
 *    at `400` (`IllegalArgumentException`), the exception's own status (a `ResponseStatusException` --
 *    `409` for an override refusal, `422` for a releases/snapshots refusal) or `500` otherwise.
 *  - Override rule (`AbstractNuGetProtocolFacade.publish` L100-124): `checkVersionAllowance` runs
 *    FIRST (a releases/snapshots refusal is `422`, even for a version that already exists -- a
 *    redeploy under `releases:false`/`snapshots:false` is `422`, never `409`), then, only when
 *    `!repoInfo.isAllowOverride() && versionExists(...)`, a `409 Conflict` ("Version <v> of package
 *    <id> already exists."). NuGet is the first protocol in this harness with a real `409`/`conflict`
 *    outcome (maven: 403; npm: an existing version is always refused regardless of allowOverride,
 *    also 403; cargo: no override rule, unconditional 400).
 *  - Package ids are stored LOWERCASED (`findOrCreatePackage` inserts `packageId.toLowerCase()`); a
 *    mixed-case publish round-trips through the flat index / registration under the lowercase
 *    spelling. Versions are stored NORMALISED (`normalizeNuGetVersion`: lowercase, trailing `.0`
 *    beyond three parts dropped, `+build` dropped, prerelease label kept as-is).
 *  - Flat version list: `GET /<repoName>/v3/package/<idLower>/index.json`, `permission: READ`.
 *    `{"versions":[...]}` (every version, listed and unlisted, lowercased, ascending -- ALL of them,
 *    not just the latest); `404` when the package does not exist at all
 *    (`AbstractNuGetPackageVersionsProtocolMethodHandler` L91-99, `ItemNotFoundException` ->
 *    `notFound()`).
 *  - Download: `GET /<repoName>/v3/package/<idLower>/<verLower>/<idLower>.<verLower>.nupkg` (and the
 *    `.nuspec` sibling), `permission: READ`, `application/octet-stream`/`application/xml`; `404` on
 *    `ItemNotFoundException`, `500` otherwise (`AbstractNuGetDownloadProtocolMethodHandler`).
 *  - Registration index: `GET /<repoName>/v3/registration/<idLower>/index.json`, `permission: READ`;
 *    `404` on `ItemNotFoundException`/`IllegalArgumentException`
 *    (`AbstractNuGetRegistrationProtocolMethodHandler`). Shape: `{ items: [ { items: [ { catalogEntry:
 *    { version, ... }, listed, packageContent, ... } ] } ] }` (`NuGetRegistrationIndexResponse` ->
 *    `NuGetRegistrationPageItem` -> `NuGetRegistrationLeafItem`, `NuGetResponseMapper.toLeafItem`).
 *  - Service index: `GET /<repoName>/v3/index.json`, `skipPreProcessor: true` -- UNAUTHENTICATED even
 *    on a private repo (`AbstractNuGetServiceIndexProtocolMethodHandler` L60-70). `{ resources: [ {
 *    "@id", "@type", comment } ] }` (`NuGetServiceIndexResponse`/`NuGetServiceIndexResource`).
 *    `NuGetServiceIndexResources.build` (L27-46) advertises `PackageBaseAddress/3.0.0` and
 *    `PackagePublish/2.0.0` both at `<base>/v3/package`, `RegistrationsBaseUrl` at
 *    `<base>/v3/registration`, `SearchQueryService` + `SearchQueryService/3.0.0-beta` at
 *    `<base>/v3/search`, `SearchAutocompleteService` + `SearchAutocompleteService/3.0.0-beta` at
 *    `<base>/v3/autocomplete` (RPS-1213/RPS-1240: the types NuGet.Client resolves; the pre-fix
 *    `/3.0.0` spellings and a non-standard `PackageDelete/2.0.0` are gone). `<base>` = `NuGetUrlBuilder.buildBaseUrl` -- built
 *    from the CURRENT request's own scheme/host/port, so it always names exactly one `/<repoName>/`
 *    segment on Repsy OS's single-tenant layout.
 *  - Auth (`NuGetAuthPreProcessor`): `extractAuthHeader` reads `Authorization` when present, else
 *    `X-NuGet-ApiKey`, else `401` + `WWW-Authenticate: Basic realm="Repsy"`.
 *    `normalizeAuthHeader` prefixes `Bearer ` onto whatever it got UNLESS it already starts with
 *    `Basic `/`Bearer `, so a raw deploy token (however delivered) is authenticated via
 *    `handleBearerAuth`, and a `Basic <base64>` value (however delivered, `Authorization` or
 *    `X-NuGet-ApiKey`) via `handleBasicAuth`. Every `AccessNotAllowedException`/`UnAuthorizedException`
 *    from either path becomes a flat `401` with that same challenge header -- there is no separate
 *    "forbidden" outcome for this protocol either (`token-ro` publish: `401`, like every other
 *    protocol in this harness).
 *
 * Step 5e (RPS-294, `tests/nuget/protocol-specific.spec.ts`) adds `rawUnlist`/`rawRelist`
 * (`DELETE`/`POST /v3/package/<idLower>/<verLower>`, confirmed against
 * `AbstractNuGetUnlistProtocolMethodHandler`/`AbstractNuGetRelistProtocolMethodHandler` before this
 * file was written, then live: `204`/`200`, the registration leaf's `listed` flips both ways, the
 * flat container keeps the version regardless -- real NuGet unlist semantics, ported from
 * `repsy-cloud`'s own `protocols/nuget/unlist`/`relist`) and `rawSearch`/`rawAutocomplete`
 * (`v3/search`/`v3/autocomplete`, confirmed live to return `totalHits`/`data[].id`/
 * `data[].registration` exactly as `NuGetSearchResponse`/`NuGetAutocompleteResponse` declare).
 */
import { zipSync } from 'fflate';

import { repoUrl as repositoryUrl } from '../repo-url.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import { randomPadding } from './padding.js';
import {
  adminCredential,
  authHeader,
  msgIdOf,
  type RawResponse,
  sha256Hex,
  withBackoff429Response,
} from './raw-http.js';

export { adminCredential, authHeader, sha256Hex, type RawResponse };

function repoUrl(repoName: string): string {
  return repositoryUrl(repoName, '');
}

/** `e2e-<runid>-<slugified scenario id>` -- matches `ProtocolAdapter.packageName`'s signature. */
export function packageName(runId: string, scenario: Scenario): string {
  return `e2e-${runId}-${scenario.id.replace(/[^a-z0-9]/gi, '-').toLowerCase()}`;
}

/** `<repoBaseUrl>/<repo>/v3/index.json` -- the URL a nuget.config `<add ... value="..."/>` names. */
export function serviceIndexUrl(repoName: string): string {
  return `${repoUrl(repoName)}v3/index.json`;
}

/**
 * `<repoBaseUrl>/<repo>/v3/package/` -- WITH the trailing slash: the real client's
 * `PackageUpdateResource.GetServiceEndpointUrl` always appends one (this file's header), and the raw
 * probe has to hit the exact same URL a real `dotnet nuget push` does.
 */
export function publishUrl(repoName: string): string {
  return `${repoUrl(repoName)}v3/package/`;
}

export function versionsPath(idLower: string): string {
  return `v3/package/${idLower}/index.json`;
}

export function nupkgPath(idLower: string, verLower: string): string {
  return `v3/package/${idLower}/${verLower}/${idLower}.${verLower}.nupkg`;
}

export function nuspecPath(idLower: string, verLower: string): string {
  return `v3/package/${idLower}/${verLower}/${idLower}.${verLower}.nuspec`;
}

export function registrationIndexPath(idLower: string): string {
  return `v3/registration/${idLower}/index.json`;
}

/**
 * `v3/package/<idLower>/<verLower>` -- NO trailing segment (distinct from `versionsPath`'s
 * `.../index.json`): the same two-path-segment shape both unlist (`DELETE`) and relist (`POST`)
 * match, confirmed against the server's own handlers
 * (`AbstractNuGetUnlistProtocolMethodHandler`/`AbstractNuGetRelistProtocolMethodHandler`, whose
 * `UNLIST_PATTERN`/`RELIST_PATTERN` match exactly two path segments under `v3/package/`) before
 * this file was written. Confirmed live: `DELETE` -> `204`, flips the registration leaf's `listed`
 * to `false`, the flat container keeps the version regardless; `POST` -> `200`, flips `listed`
 * back to `true`.
 */
export function unlistRelistPath(idLower: string, verLower: string): string {
  return `v3/package/${idLower}/${verLower}`;
}

/** `semVerLevel` is left off the query string when `undefined`, the way a client that predates
 *  SemVer 2.0.0 (RPS-1275) sends none; `dotnet package search` always sends `2.0.0`. */
export function searchPath(
  query: string,
  prerelease: boolean,
  skip = 0,
  take = 20,
  semVerLevel?: string,
): string {
  const params = new URLSearchParams({
    q: query,
    skip: String(skip),
    take: String(take),
    prerelease: String(prerelease),
  });
  if (semVerLevel !== undefined) {
    params.set('semVerLevel', semVerLevel);
  }
  return `v3/search?${params.toString()}`;
}

export function autocompletePath(
  query: string,
  prerelease: boolean,
  skip = 0,
  take = 20,
  semVerLevel?: string,
): string {
  const params = new URLSearchParams({
    q: query,
    skip: String(skip),
    take: String(take),
    prerelease: String(prerelease),
  });
  if (semVerLevel !== undefined) {
    params.set('semVerLevel', semVerLevel);
  }
  return `v3/autocomplete?${params.toString()}`;
}

/**
 * A port of the server's `NuGetPackageUtils.normalizeNuGetVersion`: lowercase, trailing zero
 * components dropped (minimum three kept: major.minor.patch), `+build` metadata dropped, the
 * pre-release suffix kept as-is. Used to build the paths a stored version is actually served/stored
 * under, since `1.0.0.0` is accepted but served/stored as `1.0.0` (see "H_normalize" in `README.md`).
 */
export function normalizeVersion(rawVersion: string): string {
  const lower = rawVersion.trim().toLowerCase();
  const plusIdx = lower.indexOf('+');
  const withoutBuild = plusIdx >= 0 ? lower.slice(0, plusIdx) : lower;
  const dashIdx = withoutBuild.indexOf('-');
  const core = dashIdx >= 0 ? withoutBuild.slice(0, dashIdx) : withoutBuild;
  const preRelease = withoutBuild.slice(core.length);

  const components = core.split('.');
  let end = components.length;
  while (end > 3 && components[end - 1] === '0') {
    end -= 1;
  }
  return components.slice(0, end).join('.') + preRelease;
}

/**
 * The literal value a real `dotnet nuget push --api-key <k>` would be given for `credential`:
 * `token` -> the raw deploy token (the panel's "Option B" -- authenticates the client's FIRST
 * request via `X-NuGet-ApiKey`, the server's Bearer path); `password` -> the literal string `any`
 * (the panel's "Option B" text VERBATIM tells users to pass their password here too, but
 * `X-NuGet-ApiKey` is only ever fed through the Bearer path server-side, so a real password there
 * would just fail -- `any` is a placeholder the client accepts and sends, then gets challenged and
 * retries with Basic from `packageSourceCredentials` instead; see "H7" in README.md); `anonymous` ->
 * `undefined` (no `--api-key` flag at all).
 */
export function nugetApiKey(credential: MaterializedCredential): string | undefined {
  if (credential.kind === 'token') {
    return credential.password;
  }
  if (credential.kind === 'password') {
    return 'any';
  }
  return undefined;
}

/**
 * The header(s) a raw PUBLISH probe should send to mirror a real `dotnet nuget push`'s FIRST
 * request: `token` -> `X-NuGet-ApiKey: <deploy token>` alone (accepted immediately, no retry);
 * `password` -> a Basic `Authorization` header (mirrors the client's SECOND request, after the
 * dummy `--api-key any` gets a 401 challenge and `packageSourceCredentials` kicks in -- see
 * `nugetApiKey`'s doc comment); `anonymous` -> no header at all.
 */
export function nugetPublishHeaders(credential: MaterializedCredential): Record<string, string> {
  if (credential.kind === 'token') {
    const token = credential.password;
    return token !== undefined ? { 'X-NuGet-ApiKey': token } : {};
  }
  return authHeader(credential);
}

/** The header(s) a raw READ probe (versions/download/registration) should send: plain Basic for
 *  both credential kinds -- `restore` has no api-key concept, only `packageSourceCredentials`. */
export function nugetReadHeaders(credential: MaterializedCredential): Record<string, string> {
  return authHeader(credential);
}

/**
 * Builds a minimal, real `.nupkg` (a zip: a root `<id>.nuspec` plus `content/e2e-marker.txt`) with
 * `fflate`, never the `dotnet`/`nuget` binary (forbidden on the host, see this story's constraints).
 * The nuspec is a ROOT zip entry (the real client's `PackageReaderBase.GetNuspecFile` requires
 * exactly one, at the root; the server finds any `.nuspec` entry by a regex over the whole archive,
 * `NuGetPackageUtils.extractNuspec`, so a root entry satisfies both). No `[Content_Types].xml`/
 * `_rels` OPC parts: the server never looks for them, and a real `dotnet restore` only ever reads
 * `<id>.nuspec` back out of the file it downloaded, never the OPC metadata.
 */
export function buildNupkg(opts: {
  packageId: string;
  version: string;
  marker?: string;
  /** Dependencies the nuspec declares (RPS-1479), rendered as `<dependencies><group ...>` (one group
   *  per `targetFramework`, a dependency without one goes into a group with no attribute). */
  dependencies?: NuspecDependency[];
  /** Target frameworks that get an EMPTY `<group targetFramework="..."/>` ("no dependencies there"). */
  emptyGroups?: string[];
  /** Bytes of random padding stored in `content/e2e-padding.bin`, for the size-limit leg (RPS-1482,
   *  `padding.ts`). */
  padBytes?: number;
  /** Extra nuspec metadata (RPS-1483, what the panel's package and version details show); none by default. */
  metadata?: NuspecExtraMetadata;
}): Buffer {
  const marker = opts.marker ?? `e2e ${opts.packageId}@${opts.version}`;
  const extra = opts.metadata ?? {};
  const nuspec =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    '<package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">\n' +
    '  <metadata>\n' +
    `    <id>${opts.packageId}</id>\n` +
    `    <version>${opts.version}</version>\n` +
    (extra.title === undefined ? '' : `    <title>${extra.title}</title>\n`) +
    '    <authors>repsy-e2e</authors>\n' +
    `    <description>e2e ${opts.packageId}@${opts.version}</description>\n` +
    (extra.tags === undefined ? '' : `    <tags>${extra.tags}</tags>\n`) +
    (extra.iconUrl === undefined ? '' : `    <iconUrl>${extra.iconUrl}</iconUrl>\n`) +
    (extra.licenseUrl === undefined ? '' : `    <licenseUrl>${extra.licenseUrl}</licenseUrl>\n`) +
    (extra.projectUrl === undefined ? '' : `    <projectUrl>${extra.projectUrl}</projectUrl>\n`) +
    (extra.repositoryUrl === undefined
      ? ''
      : `    <repository type="git" url="${extra.repositoryUrl}" />\n`) +
    (extra.readme === undefined ? '' : '    <readme>README.md</readme>\n') +
    renderNuspecDependencies(opts.dependencies ?? [], opts.emptyGroups ?? []) +
    '  </metadata>\n' +
    '</package>\n';

  const entries: Record<string, Uint8Array> = {
    [`${opts.packageId}.nuspec`]: new TextEncoder().encode(nuspec),
    'content/e2e-marker.txt': new TextEncoder().encode(marker),
  };
  if (extra.readme !== undefined) {
    entries['README.md'] = new TextEncoder().encode(extra.readme);
  }
  if (opts.padBytes !== undefined) {
    entries['content/e2e-padding.bin'] = randomPadding(opts.padBytes);
  }
  const zipped = zipSync(entries, { level: 0 });
  return Buffer.from(zipped);
}

/** The optional nuspec elements of `buildNupkg({ metadata })` (RPS-1483); `readme` is the text of the `README.md` the
 *  nuspec's `<readme>` names. */
export interface NuspecExtraMetadata {
  title?: string;
  tags?: string;
  iconUrl?: string;
  licenseUrl?: string;
  projectUrl?: string;
  repositoryUrl?: string;
  readme?: string;
}

/** One `<dependency id="..." version="...">` of a nuspec (RPS-1479). `range` is a NuGet version
 *  range (`[1.0.0, )`, `[1.0.0]`, `1.0.0`); omitted, the attribute is left out. */
export interface NuspecDependency {
  id: string;
  range?: string;
  targetFramework?: string;
}

function renderNuspecDependencies(deps: NuspecDependency[], emptyGroups: string[]): string {
  if (deps.length === 0 && emptyGroups.length === 0) {
    return '';
  }
  const groups = new Map<string, NuspecDependency[]>();
  for (const dep of deps) {
    const key = dep.targetFramework ?? '';
    groups.set(key, [...(groups.get(key) ?? []), dep]);
  }
  let xml = '    <dependencies>\n';
  for (const [tfm, members] of groups) {
    xml += tfm === '' ? '      <group>\n' : `      <group targetFramework="${tfm}">\n`;
    for (const dep of members) {
      const version = dep.range === undefined ? '' : ` version="${dep.range}"`;
      xml += `        <dependency id="${dep.id}"${version} />\n`;
    }
    xml += '      </group>\n';
  }
  for (const tfm of emptyGroups) {
    xml += `      <group targetFramework="${tfm}" />\n`;
  }
  return `${xml}    </dependencies>\n`;
}

async function rawRequest(url: string, init: RequestInit): Promise<RawResponse> {
  return withBackoff429Response(async () => {
    const res = await fetch(url, init);
    const bytes = Buffer.from(await res.arrayBuffer());
    return { status: res.status, msgId: msgIdOf(bytes), body: bytes };
  });
}

/**
 * Raw `PUT` of a `multipart/form-data` publish, exactly what a real `dotnet nuget push` sends
 * (`PackageUpdateResource.cs`: a `MultipartFormDataContent` whose one part is named `package`/
 * `package.nupkg`). Deliberately never sets `Content-Type` by hand -- `fetch`'s own `FormData`
 * handling adds the `multipart/form-data; boundary=...` header itself.
 */
export async function rawPublish(
  repoName: string,
  credential: MaterializedCredential,
  nupkgBytes: Buffer,
): Promise<RawResponse> {
  const form = new FormData();
  form.append('package', new Blob([new Uint8Array(nupkgBytes)]), 'package.nupkg');
  return rawRequest(publishUrl(repoName), {
    method: 'PUT',
    headers: nugetPublishHeaders(credential),
    body: form,
  });
}

/** Raw, always-unauthenticated `GET` of the service index (`skipPreProcessor: true`). */
export async function rawGetServiceIndex(repoName: string): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}v3/index.json`, {});
}

/** Raw `GET` of the flat version list for one (lowercased) package id. */
export async function rawGetVersions(
  repoName: string,
  credential: MaterializedCredential,
  idLower: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${versionsPath(idLower)}`, {
    headers: nugetReadHeaders(credential),
  });
}

/** Raw `GET` of the `.nupkg` bytes at their canonical stored path. */
export async function rawDownloadNupkg(
  repoName: string,
  credential: MaterializedCredential,
  idLower: string,
  verLower: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${nupkgPath(idLower, verLower)}`, {
    headers: nugetReadHeaders(credential),
  });
}

/** Raw `GET` of the `.nuspec` bytes at their canonical stored path. */
export async function rawDownloadNuspec(
  repoName: string,
  credential: MaterializedCredential,
  idLower: string,
  verLower: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${nuspecPath(idLower, verLower)}`, {
    headers: nugetReadHeaders(credential),
  });
}

/** Raw `GET` of the registration index for one (lowercased) package id. */
export async function rawGetRegistrationIndex(
  repoName: string,
  credential: MaterializedCredential,
  idLower: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${registrationIndexPath(idLower)}`, {
    headers: nugetReadHeaders(credential),
  });
}

/**
 * Raw `DELETE` unlist of one (lowercased id, lowercased/normalized version) leaf -- NuGet's own
 * "unlist" convention (`PackagePublish/2.0.0`'s own `DELETE`, not the non-standard `PackageDelete/
 * 2.0.0` service the index also advertises -- see `nuget-raw.ts`'s file header and README.md's H6).
 * `permission: WRITE` (`AbstractNuGetUnlistProtocolMethodHandler.getProperties`).
 */
export async function rawUnlist(
  repoName: string,
  credential: MaterializedCredential,
  idLower: string,
  verLower: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${unlistRelistPath(idLower, verLower)}`, {
    method: 'DELETE',
    headers: nugetPublishHeaders(credential),
  });
}

/** Raw `POST` relist of one (lowercased id, lowercased/normalized version) leaf, the exact inverse
 *  of `rawUnlist`. `permission: WRITE`. */
export async function rawRelist(
  repoName: string,
  credential: MaterializedCredential,
  idLower: string,
  verLower: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${unlistRelistPath(idLower, verLower)}`, {
    method: 'POST',
    headers: nugetPublishHeaders(credential),
  });
}

/** Raw `GET` search (`v3/search?q=...`), `permission: READ`. */
export async function rawSearch(
  repoName: string,
  credential: MaterializedCredential,
  query: string,
  prerelease = true,
  semVerLevel?: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}${searchPath(query, prerelease, 0, 20, semVerLevel)}`, {
    headers: nugetReadHeaders(credential),
  });
}

/** Raw `GET` autocomplete (`v3/autocomplete?q=...`), `permission: READ`. */
export async function rawAutocomplete(
  repoName: string,
  credential: MaterializedCredential,
  query: string,
  prerelease = true,
  semVerLevel?: string,
): Promise<RawResponse> {
  return rawRequest(
    `${repoUrl(repoName)}${autocompletePath(query, prerelease, 0, 20, semVerLevel)}`,
    { headers: nugetReadHeaders(credential) },
  );
}

/** One `NuGetSearchData` entry the way the server serializes it: `id` is `@JsonProperty("id")` on
 *  the java field named `packageId` (the spec's own `id` key), `registration` is the URL to the
 *  package's registration index. Confirmed live: `data[].id` is always the LOWERCASED, stored
 *  spelling (`findOrCreatePackage` stores `packageId.toLowerCase()`, same as the registration/flat
 *  index -- H8), so a caller matches it against a pushed id case-insensitively. */
export interface SearchResultData {
  id: string;
  version: string;
  registration: string;
  /** The `version` of each `versions[]` entry, in the order the server lists them. */
  versions: string[];
}

export interface SearchResponse {
  totalHits: number;
  data: SearchResultData[];
}

/** Parses `{"totalHits":N,"data":[{"id","version","registration",...}]}` (`NuGetSearchResponse`). */
export function parseSearchResponse(body: Buffer): SearchResponse {
  const parsed = JSON.parse(body.toString('utf8')) as {
    totalHits?: unknown;
    data?: {
      id?: unknown;
      version?: unknown;
      registration?: unknown;
      versions?: { version?: unknown }[];
    }[];
  };
  const data = (parsed.data ?? [])
    .filter(
      (d) =>
        typeof d.id === 'string' &&
        typeof d.version === 'string' &&
        typeof d.registration === 'string',
    )
    .map((d) => ({
      id: d.id as string,
      version: d.version as string,
      registration: d.registration as string,
      versions: (d.versions ?? [])
        .map((v) => v.version)
        .filter((v): v is string => typeof v === 'string'),
    }));
  return { totalHits: typeof parsed.totalHits === 'number' ? parsed.totalHits : 0, data };
}

/** Autocomplete's own shape (`NuGetAutocompleteResponse`): `{"totalHits":N,"data":["<id>",...]}`,
 *  bare id strings, never `SearchResultData` objects. */
export interface AutocompleteResponse {
  totalHits: number;
  data: string[];
}

export function parseAutocompleteResponse(body: Buffer): AutocompleteResponse {
  const parsed = JSON.parse(body.toString('utf8')) as { totalHits?: unknown; data?: unknown };
  const data = Array.isArray(parsed.data)
    ? (parsed.data as unknown[]).filter((d): d is string => typeof d === 'string')
    : [];
  return { totalHits: typeof parsed.totalHits === 'number' ? parsed.totalHits : 0, data };
}

/** Parses `{"versions":[...]}`; `[]` when the body is not that shape. */
export function parseVersions(body: Buffer): string[] {
  try {
    const parsed = JSON.parse(body.toString('utf8')) as { versions?: unknown };
    return Array.isArray(parsed.versions) ? (parsed.versions as string[]) : [];
  } catch {
    return [];
  }
}

/** One resource entry of the service index's `resources` array (`NuGetServiceIndexResource`). */
export interface ServiceIndexResource {
  id: string;
  type: string;
  comment?: string;
}

/** Parses the service index's `{ resources: [ { "@id", "@type", comment } ] }` body. */
export function parseServiceIndex(body: Buffer): ServiceIndexResource[] {
  const parsed = JSON.parse(body.toString('utf8')) as {
    resources?: { '@id'?: unknown; '@type'?: unknown; comment?: unknown }[];
  };
  return (parsed.resources ?? [])
    .filter((r) => typeof r['@id'] === 'string' && typeof r['@type'] === 'string')
    .map((r) => ({
      id: r['@id'] as string,
      type: r['@type'] as string,
      comment: typeof r.comment === 'string' ? r.comment : undefined,
    }));
}

/** One registration leaf (`NuGetRegistrationLeafItem`) the way `parseRegistrationIndex` flattens it.
 *  `packageId` is `catalogEntry.id` (`@JsonProperty("id")` on `NuGetCatalogEntry.packageId`) -- the
 *  spelling the registration ECHOES, which is always the lowercased, stored spelling (see
 *  `nuget.ts`'s mixed-case round-trip test, "H8"). */
export interface RegistrationLeaf {
  version: string;
  listed: boolean;
  packageContent: string;
  packageId: string;
}

/**
 * Flattens `{ items: [ { items: [ { catalogEntry: { id, version }, listed, packageContent } ] } ] }`
 * (`NuGetRegistrationIndexResponse` -> `NuGetRegistrationPageItem` -> `NuGetRegistrationLeafItem`)
 * into one array of leaves, across every page.
 */
export function parseRegistrationIndex(body: Buffer): RegistrationLeaf[] {
  const parsed = JSON.parse(body.toString('utf8')) as {
    items?: {
      items?: {
        catalogEntry?: { id?: unknown; version?: unknown };
        listed?: unknown;
        packageContent?: unknown;
      }[];
    }[];
  };
  const leaves: RegistrationLeaf[] = [];
  for (const page of parsed.items ?? []) {
    for (const item of page.items ?? []) {
      const version = item.catalogEntry?.version;
      if (typeof version === 'string') {
        leaves.push({
          version,
          listed: item.listed === true,
          packageContent: typeof item.packageContent === 'string' ? item.packageContent : '',
          packageId: typeof item.catalogEntry?.id === 'string' ? item.catalogEntry.id : '',
        });
      }
    }
  }
  return leaves;
}

/** `{"errors":[{"message":"..."}]}` -- `NuGetErrorResponse.of`. `undefined` when not that shape. */
export function nugetErrorMessage(body: Buffer): string | undefined {
  try {
    const parsed = JSON.parse(body.toString('utf8')) as { errors?: { message?: unknown }[] };
    const message = parsed.errors?.[0]?.message;
    return typeof message === 'string' ? message : undefined;
  } catch {
    return undefined;
  }
}

/** One `dependencyGroups` entry of a registration leaf's `catalogEntry` (RPS-1479). */
export interface RegistrationDependencyGroup {
  targetFramework?: string;
  dependencies: { id: string; range?: string }[];
}

/** The `catalogEntry.dependencyGroups` of one registration leaf document
 *  (`rawGetRegistrationLeaf`); `[]` when it carries none. */
export function parseLeafDependencyGroups(body: Buffer): RegistrationDependencyGroup[] {
  const parsed = JSON.parse(body.toString('utf8')) as {
    catalogEntry?: {
      dependencyGroups?: {
        targetFramework?: string;
        dependencies?: { id?: string; range?: string }[];
      }[];
    };
  };
  return (parsed.catalogEntry?.dependencyGroups ?? []).map((g) => ({
    ...(g.targetFramework === undefined ? {} : { targetFramework: g.targetFramework }),
    dependencies: (g.dependencies ?? []).map((d) => ({
      id: d.id ?? '',
      ...(d.range === undefined ? {} : { range: d.range }),
    })),
  }));
}

/** Raw `GET` of one version's own registration leaf, `v3/registration/<idLower>/<verLower>.json`
 *  (`NuGetRegistrationLeafResponse`; the registration INDEX inlines its own copy of every leaf). */
export async function rawGetRegistrationLeaf(
  repoName: string,
  credential: MaterializedCredential,
  idLower: string,
  verLower: string,
): Promise<RawResponse> {
  return rawRequest(`${repoUrl(repoName)}v3/registration/${idLower}/${verLower}.json`, {
    headers: nugetReadHeaders(credential),
  });
}
