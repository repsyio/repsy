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
 * Raw HTTP helpers for the Ruby gem (RubyGems/Bundler) protocol, next to the real-client adapter
 * (`ruby.ts`), built on `raw-http.ts`, the Ruby analogue of `pypi-raw.ts`/`golang-raw.ts`. Every wire
 * fact below was read from the server source (`repsy-protocols/ruby/**`,
 * `repsy-backend/.../protocols/ruby/**`) and then confirmed live against a running instance (see
 * `README.md`'s "Ruby runner" section for the raw evidence and every H/RB-number these tests
 * reference).
 *
 * **H1, the single most consequential finding of this step, is REFUTED**: a real `bundle install`
 * against a gem published on Repsy OS succeeds completely (exit 0), resolving through
 * `/versions` -> `/info/<gem>` and downloading the gem directly -- it never requests
 * `quick/Marshal.4.8/*.gemspec.rz` at all for a plain gem with no dependencies. Confirmed live: the
 * cached `.gem` file bundler leaves under `<BUNDLE_PATH>/ruby/<abi>/cache/` is byte-identical
 * (sha256-equal) to what was published. `ruby.ts`'s `resolve()` therefore drives real `bundle
 * install`, and there is deliberately NO `knownConsumeFailure` hook on `rubyAdapter` -- every
 * scenario's consume side is asserted for real, exactly like maven/npm/pypi.
 *
 *  - Base URL for every client: `<repoBaseUrl>/<repoName>` (`RubyPathParser`: `^/<repoName>(/.*)?`,
 *    repo name lower-cased, no `/ruby/` prefix -- single-tenant, like pypi's `/<repo>/`).
 *  - Publish: `POST /<repo>/api/v1/gems`, `permission: WRITE`, raw request body (never multipart).
 *    `Content-Length` over `repsy.ruby.max-gem-size` (500MB default) is refused with 413 before any
 *    of the body is read. Success: `200 text/plain`, `"Successfully registered gem: <name>
 *    (<version>)"`. An unparseable gem (a bad tar, a `metadata.gz` GemspecParser cannot load) is `400
 *    text/plain "invalidGemFile"`.
 *  - Upload pipeline (`GemspecParser.parse` -> `RubyGemServiceImpl.publishGem` ->
 *    `AbstractRubyProtocolFacade.storeGem`, confirmed live and read from source):
 *    `metadata.gz` is gzipped **YAML** (`Gem::Specification#to_yaml`/`Gem::Specification.from_yaml`),
 *    loaded through SnakeYAML's `SafeConstructor` with every `!ruby/...` mapping tag retagged to a
 *    plain map first -- **not** Marshal (only the index side channels, `specs.4.8.gz` and the
 *    (unimplemented, RPS-1233) `quick/Marshal.4.8/*.gemspec.rz`, use Marshal). `name`/`version` are
 *    required (`400 gemNameMissing`/`gemVersionMissing`); `platform` defaults to `"ruby"`; every
 *    identifier has a column-length limit (RPS-1071, `400 gem{Name,Version,Platform,
 *    RequiredRubyVersion}TooLong`). The DB row (`ruby_gem`/`ruby_gem_version`) is created/updated and
 *    FLUSHED (a concurrent duplicate-version race becomes `409` via the unique index) BEFORE the file
 *    is written (RPS-1060, re-verified live: a re-push under `allowOverride:false` changes nothing on
 *    disk, RB-0). An existing, non-yanked version under `allowOverride:true` is overwritten in place
 *    (checksum/metadata/deps replaced); an existing version that is EITHER yanked OR
 *    `allowOverride:false` is refused with **`409 gemVersionAlreadyExists`** (`ErrorHandler` maps
 *    `ItemAlreadyExistException` to `CONFLICT`) -- confirmed live, a REAL, genuine conflict outcome
 *    (like nuget/helm/golang), not maven's 403.
 *  - `GET /<repo>/gems/<file>.gem`: `permission: READ`, `application/octet-stream`; every exception
 *    (including a yanked version, `checkNotYanked`) becomes a bodyless `404`.
 *  - `GET /<repo>/versions`: `text/plain`, `created_at: <now>\n---\n<name> <v1,v2,-yankedv> <md5>\n...`
 *    (sorted by name); the `created_at` value changes on every request (never compare it across
 *    calls). The per-gem `md5` is `md5Hex` of that gem's own `/info` body (`CompactIndexFormatter`).
 *  - `GET /<repo>/info/<gem>`: `text/plain`, one line per version **including yanked ones, prefixed
 *    `-`** (RPS-1235: the compact-index spec says a yanked version should be OMITTED from `/info`
 *    entirely, only excluded there -- Repsy instead lists it with a `-` prefix, confirmed live).
 *    **No `ruby:`/`rubygems:` requirement keys are ever emitted** (RB-2, confirmed live:
 *    `CompactIndexFormatter.appendVersionLine` never writes them, even though
 *    `required_ruby_version` is parsed and stored) -- this is exactly why Bundler's `FetchMetadata`
 *    lazy remote-spec fetch is never triggered for a plain gem (H1's refutation). Unknown gem ->
 *    `404 gemNotFound`.
 *  - `GET /<repo>/names`: preamble + one name per line.
 *  - `GET /<repo>/specs.4.8.gz` / `latest_specs.4.8.gz` / `prerelease_specs.4.8.gz`: a Marshal 4.8
 *    array of `[name, Gem::Version, platform]`, compressed with `java.util.zip.DeflaterOutputStream`
 *    -- **raw zlib/RFC1950, NOT gzip** (RPS-1234, confirmed live: `gunzipSync` throws, `inflateSync`
 *    succeeds and yields the Marshal `\x04\x08` header). Prerelease = "version contains a letter".
 *  - `GET /<repo>/quick/Marshal.4.8/<name>-<ver>[-<platform>].gemspec.rz`: **`404 unknownPath`** (RPS-1233,
 *    grep- and live-confirmed: no backend class extends `AbstractRubyGemspecHandler`, even though the
 *    abstract handler/writer exist in `repsy-protocols/ruby`). Breaks `gem install`/`gem fetch`
 *    (H12/RPS-1233 via `gem`, confirmed live below), but -- per H1's refutation -- NOT `bundle install`.
 *  - `DELETE /<repo>/api/v1/gems/yank` (form-encoded `gem_name`, `version`, optional `platform`
 *    default `ruby`): `MANAGE` permission (admin, or any non-read-only deploy token -- a read-only
 *    token or a `USER`-role password gets `401`, confirmed live). `200 text/plain "Successfully
 *    yanked gem: ..."`; already yanked -> `400 gemVersionAlreadyYanked`; unknown -> `404`. A yanked
 *    version's `.gem` file answers `404` on download (confirmed live) -- unlike real rubygems.org,
 *    which keeps serving a yanked file's bytes so existing lockfiles still resolve (RPS-1238, an
 *    observation, not independently forced beyond this one live check). A yanked version CANNOT be
 *    re-pushed even under `allowOverride:true` (still `409`, confirmed live); a version removed via
 *    the panel API (a real delete, not a yank) CAN be re-pushed with `200`.
 *  - `HEAD` on ANY path (an existing one included) is `200` empty, existence never checked (RPS-1237,
 *    confirmed live -- the pypi/nuget analogue).
 *  - Auth (`RubyAuthPreProcessor`, priority 100): skipped only for a public-repo READ. A
 *    missing/unparseable `Authorization` is a bodyless `401` + `WWW-Authenticate: Basic
 *    realm="Repsy Managed Repository"`. `normalizeAuthHeader` Bearer-prefixes any value that does not
 *    already start with `Basic `/`Bearer ` (the cargo/golang trick): `RubyAuthComponent` is a bare
 *    `ProtocolAuthService` with no overrides, so `handleBearerAuth` tries a raw deploy-token secret
 *    first, `handleBasicAuth` tries the PASSWORD as a deploy token first then falls back to
 *    username/password -- a read-only deploy token on a WRITE is the same flat `401` every other
 *    protocol in this harness gives (confirmed live), never a distinct `403`. Single-hop, no token
 *    exchange.
 *  - `releases`/`snapshots` repo settings are never read by any Ruby code (grep-confirmed: no
 *    `isReleases|isSnapshots` hit under either Ruby package) -- `catalog.ts` never adds `ruby` to the
 *    maven/nuget-only `releases`/`snapshots` scenarios, same as docker/helm/pypi/golang.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

import mustache from 'mustache';

import { env } from '../env.js';
import { boundedSemverVersion } from '../scenarios/coordinates.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import { buildTar } from './docker-image.js';
import {
  adminCredential,
  authHeader,
  msgIdOf,
  type RawResponse,
  sha256Hex,
  withBackoff429Response,
} from './raw-http.js';

export { adminCredential, authHeader, msgIdOf, sha256Hex, type RawResponse };

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const TEMPLATES_DIR = path.resolve(__dirname, '../packages/ruby');

function repoUrl(repoName: string): string {
  return `${env.repoBaseUrl}/${repoName}`;
}

/**
 * `e2e_<runid>_<scenario.id with [^a-z0-9] -> _>` -- UNDERSCORES, deliberately NOT hyphens (unlike
 * every other protocol's `packageName`). Reason (RPS-1236, confirmed live): a gem name containing a
 * hyphen immediately followed by a digit (`foo-2fa`) cannot be downloaded --
 * `AbstractRubyStorageService#extractGemName`'s `VERSION_START` pattern (`-(?=\d)`) cuts the
 * filename at the first such boundary, so a name published as `foo-2fa` is looked up under storage
 * key `foo` and 404s. `REPSY_E2E_RUN_ID` can start with a digit, so a hyphenated name risks tripping
 * this exact bug by accident on every run; an underscore-only name avoids it entirely.
 */
export function packageName(runId: string, scenario: Scenario): string {
  return `e2e_${runId}_${scenario.id.replace(/[^a-z0-9]/gi, '_').toLowerCase()}`;
}

/** `Gem::Version::VERSION_PATTERN` accepts `0.<secs>.<seq>` directly (dotted non-negative integers,
 *  no letter so never a Ruby "prerelease"). `versionType` is ignored: Ruby has no release/snapshot
 *  repo-setting distinction (`releases`/`snapshots` are never read by any Ruby code, grep-confirmed).
 *  A literal `-` in a version string is rewritten to `.pre.` by `Gem::Version#initialize` before
 *  anything is emitted, so this scheme (which never contains `-`) round-trips unchanged. */
export function gemVersion(): string {
  return boundedSemverVersion();
}

export function gemFilename(name: string, version: string, platform = 'ruby'): string {
  return platform === 'ruby' ? `${name}-${version}.gem` : `${name}-${version}-${platform}.gem`;
}

export function publishRelPath(): string {
  return 'api/v1/gems';
}

export function yankRelPath(): string {
  return 'api/v1/gems/yank';
}

export function versionsRelPath(): string {
  return 'versions';
}

export function namesRelPath(): string {
  return 'names';
}

export function infoRelPath(name: string): string {
  return `info/${name}`;
}

export function gemRelPath(filename: string): string {
  return `gems/${filename}`;
}

export type SpecsKind = 'specs' | 'latest_specs' | 'prerelease_specs';

export function specsRelPath(kind: SpecsKind = 'specs'): string {
  return `${kind}.4.8.gz`;
}

export function gemspecRzRelPath(name: string, version: string, platform = 'ruby'): string {
  const base = platform === 'ruby' ? `${name}-${version}` : `${name}-${version}-${platform}`;
  return `quick/Marshal.4.8/${base}.gemspec.rz`;
}

export function publishUrl(repoName: string): string {
  return `${repoUrl(repoName)}/${publishRelPath()}`;
}

export function md5Hex(value: string | Buffer): string {
  return createHash('md5').update(value).digest('hex');
}

/**
 * A hand-built `.gem` (a plain ustar tar of `metadata.gz`/`data.tar.gz`/`checksums.yaml.gz`, `fflate`
 * is not needed -- `docker-image.ts`'s `buildTar` + `node:zlib` suffice), built entirely in TypeScript
 * -- deliberately never `gem build` (this story's own constraint; confirmed live/H2: the TS-built gem
 * passes BOTH `gem push`'s client-side `Gem::Package#verify` -- which requires valid
 * `checksums.yaml.gz`/`metadata.gz`/`data.tar.gz` entries -- AND Repsy's own `GemspecParser`, with no
 * `gem build` fallback ever needed). `metadata.gz` is gzipped YAML (`metadata.template.yaml`, mustache
 * with triple-brace interpolation throughout -- the npm lesson: double-brace HTML-escapes `/`/`=`/`'`,
 * which would corrupt a `!ruby/object:...` tag or a version string). Entry order in the outer tar is
 * `metadata.gz`, `data.tar.gz`, `checksums.yaml.gz`, matching a real `gem build`'s own output.
 */
export interface BuiltGem {
  name: string;
  version: string;
  platform: string;
  marker: string;
  filename: string;
  bytes: Buffer;
  sha256Hex: string;
  metadataYaml: string;
}

let metadataTemplateCache: string | undefined;
let libTemplateCache: string | undefined;

async function metadataTemplate(): Promise<string> {
  metadataTemplateCache ??= await fs.readFile(
    path.join(TEMPLATES_DIR, 'metadata.template.yaml'),
    'utf8',
  );
  return metadataTemplateCache;
}

async function libTemplate(): Promise<string> {
  libTemplateCache ??= await fs.readFile(path.join(TEMPLATES_DIR, 'lib.template.rb'), 'utf8');
  return libTemplateCache;
}

export async function buildGem(opts: {
  name: string;
  version: string;
  platform?: string;
  marker?: string;
  requiredRubyVersion?: string;
}): Promise<BuiltGem> {
  const platform = opts.platform ?? 'ruby';
  const marker = opts.marker ?? createHash('sha256').update(`${Math.random()}`).digest('hex');

  const template = await metadataTemplate();
  const metadataYaml = mustache.render(template, {
    name: opts.name,
    version: opts.version,
    platform,
    marker,
  });
  const metadataGz = zlib.gzipSync(Buffer.from(metadataYaml, 'utf8'));

  const libRb = mustache.render(await libTemplate(), { marker });
  const dataTar = buildTar([
    { name: `lib/${opts.name}.rb`, data: Buffer.from(libRb, 'utf8') },
    { name: 'e2e-marker.txt', data: Buffer.from(`${marker}\n`, 'utf8') },
  ]);
  const dataTarGz = zlib.gzipSync(dataTar);

  const checksumsYaml =
    '---\n' +
    'SHA256:\n' +
    `  metadata.gz: ${sha256Hex(metadataGz)}\n` +
    `  data.tar.gz: ${sha256Hex(dataTarGz)}\n` +
    'SHA512:\n' +
    `  metadata.gz: ${createHash('sha512').update(metadataGz).digest('hex')}\n` +
    `  data.tar.gz: ${createHash('sha512').update(dataTarGz).digest('hex')}\n`;
  const checksumsYamlGz = zlib.gzipSync(Buffer.from(checksumsYaml, 'utf8'));

  const bytes = buildTar([
    { name: 'metadata.gz', data: metadataGz },
    { name: 'data.tar.gz', data: dataTarGz },
    { name: 'checksums.yaml.gz', data: checksumsYamlGz },
  ]);

  return {
    name: opts.name,
    version: opts.version,
    platform,
    marker,
    filename: gemFilename(opts.name, opts.version, platform),
    bytes,
    sha256Hex: sha256Hex(bytes),
    metadataYaml,
  };
}

/** Like `buildGem`, but the caller supplies the outer tar entries directly -- for a test that
 *  deliberately builds a malformed/non-conforming `.gem` (e.g. no `metadata.gz` entry at all, or one
 *  that is not valid YAML). */
export function buildRawGem(entries: { name: string; data: Buffer }[]): {
  bytes: Buffer;
  sha256Hex: string;
} {
  const bytes = buildTar(entries);
  return { bytes, sha256Hex: sha256Hex(bytes) };
}

/** What `gem push --key repsy` would send as `Authorization` for `credential`, mirroring
 *  `gemcutter_utilities.rb`'s own precedence (`ENV["GEM_HOST_API_KEY"]` wins, and this harness never
 *  uses a credentials file): the raw deploy-token secret for a `token`-kind credential (the server
 *  tries a Bearer-prefixed raw string as a deploy token first), `Basic <base64(user:pass)>` for a
 *  `password`-kind credential (dispatched to `handleBasicAuth` unchanged), `undefined` for
 *  `anonymous` (no header sent at all, never an empty string). */
export function apiKeyFor(credential: MaterializedCredential): string | undefined {
  if (credential.transport !== 'basic') {
    return undefined;
  }
  if (credential.kind === 'token') {
    return credential.password ?? '';
  }
  const basic = Buffer.from(`${credential.username ?? ''}:${credential.password ?? ''}`).toString(
    'base64',
  );
  return `Basic ${basic}`;
}

/** `Authorization` header value exactly as a real `gem push`/raw probe would send it, in one of
 *  three spellings -- for `registry-rules.spec.ts`'s Authorization-spelling pins (mirrors
 *  golang/cargo's own "how does an unprefixed token get treated" tests). `'api-key'` is what
 *  `apiKeyFor` produces (the real client's own behaviour); `'basic'`/`'bearer'` force a spelling a
 *  raw probe can still send even when it differs from what a real client would choose for that
 *  credential kind. */
export function rawAuthHeaderFor(
  credential: MaterializedCredential,
  opts?: { spelling?: 'api-key' | 'basic' | 'bearer' },
): Record<string, string> {
  const spelling = opts?.spelling ?? 'api-key';
  if (credential.transport !== 'basic') {
    return {};
  }
  if (spelling === 'basic') {
    return authHeader(credential);
  }
  const value = apiKeyFor(credential);
  if (value === undefined) {
    return {};
  }
  if (spelling === 'bearer' && !value.startsWith('Basic ')) {
    return { Authorization: `Bearer ${value}` };
  }
  return { Authorization: value };
}

export interface RubyRawResponse extends RawResponse {
  contentType?: string;
}

async function rawFetch(url: string, init: RequestInit): Promise<RubyRawResponse> {
  let last: RubyRawResponse | undefined;
  await withBackoff429Response(async () => {
    const res = await fetch(url, init);
    const bytes = Buffer.from(await res.arrayBuffer());
    last = {
      status: res.status,
      msgId: msgIdOf(bytes),
      body: bytes,
      contentType: res.headers.get('content-type') ?? undefined,
    };
    return last;
  });
  return last as RubyRawResponse;
}

/** Raw `POST` of a gem's bytes to `/<repo>/api/v1/gems`, exactly what a real `gem push` sends
 *  (raw body, `Content-Type: application/octet-stream`, `Authorization` = `apiKeyFor(credential)`
 *  unless overridden). */
export async function rawPublish(
  repoName: string,
  credential: MaterializedCredential,
  bytes: Buffer,
  opts?: { authorizationOverride?: string; contentType?: string },
): Promise<RubyRawResponse> {
  const headers: Record<string, string> =
    opts?.authorizationOverride !== undefined
      ? { Authorization: opts.authorizationOverride }
      : rawAuthHeaderFor(credential);
  headers['Content-Type'] = opts?.contentType ?? 'application/octet-stream';
  return rawFetch(publishUrl(repoName), { method: 'POST', headers, body: new Uint8Array(bytes) });
}

/** Raw form-encoded `DELETE /<repo>/api/v1/gems/yank`, exactly what `gem yank` sends. */
export async function rawYank(
  repoName: string,
  credential: MaterializedCredential,
  opts: { gemName: string; version: string; platform?: string },
): Promise<RubyRawResponse> {
  const form = new URLSearchParams();
  form.set('gem_name', opts.gemName);
  form.set('version', opts.version);
  if (opts.platform !== undefined) {
    form.set('platform', opts.platform);
  }
  const headers: Record<string, string> = {
    ...rawAuthHeaderFor(credential),
    'Content-Type': 'application/x-www-form-urlencoded',
  };
  return rawFetch(`${repoUrl(repoName)}/${yankRelPath()}?${form.toString()}`, {
    method: 'DELETE',
    headers,
  });
}

export async function rawGet(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
): Promise<RubyRawResponse> {
  return rawFetch(`${repoUrl(repoName)}/${relPath}`, { headers: rawAuthHeaderFor(credential) });
}

/** Raw `HEAD` of any path (RPS-1237: always `200`, existence never checked). */
export async function rawHead(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
): Promise<RubyRawResponse> {
  return rawFetch(`${repoUrl(repoName)}/${relPath}`, {
    method: 'HEAD',
    headers: rawAuthHeaderFor(credential),
  });
}

export async function rawDownload(
  repoName: string,
  credential: MaterializedCredential,
  filename: string,
): Promise<RubyRawResponse> {
  return rawGet(repoName, credential, gemRelPath(filename));
}

/** `/versions`' body -> `{ [gemName]: { versionsCsv, md5 } }`. The `-` yanked-prefix and any
 *  `-<platform>` suffix are kept verbatim in `versionsCsv` (callers that need the plain version list
 *  strip them themselves -- most callers only need `md5`). */
export interface ParsedVersionsIndex {
  [gemName: string]: { versionsCsv: string; md5: string };
}

export function parseVersionsIndex(body: Buffer): ParsedVersionsIndex {
  const text = body.toString('utf8');
  const lines = text
    .split('\n')
    .slice(2)
    .filter((l) => l.length > 0); // skip the 2-line preamble
  const result: ParsedVersionsIndex = {};
  for (const line of lines) {
    const [name, versionsCsv, md5] = line.split(' ');
    if (name && versionsCsv !== undefined && md5 !== undefined) {
      result[name] = { versionsCsv, md5 };
    }
  }
  return result;
}

/** `/info/<gem>`'s body -> one entry per line: `version` (without its `-` yanked prefix or
 *  `-<platform>` suffix are parsed out too), `yanked`, `platform`, `dependenciesRaw` (the
 *  pipe-preceding text, empty for a yanked line), `checksum`. */
export interface ParsedInfoLine {
  version: string;
  platform: string;
  yanked: boolean;
  dependenciesRaw: string;
  checksum: string;
}

export function parseInfo(body: Buffer): ParsedInfoLine[] {
  const text = body.toString('utf8');
  const lines = text
    .split('\n')
    .slice(2)
    .filter((l) => l.length > 0);
  return lines.map((line) => {
    const [left, right] = line.split('|');
    const checksum = (right ?? '').replace(/^checksum:/, '');
    const yanked = left.startsWith('-');
    const withoutYank = yanked ? left.slice(1) : left;
    const spaceIdx = withoutYank.indexOf(' ');
    const versionAndPlatform = spaceIdx >= 0 ? withoutYank.slice(0, spaceIdx) : withoutYank;
    const dependenciesRaw = spaceIdx >= 0 ? withoutYank.slice(spaceIdx + 1) : '';
    const dashIdx = versionAndPlatform.indexOf('-');
    const version = dashIdx >= 0 ? versionAndPlatform.slice(0, dashIdx) : versionAndPlatform;
    const platform = dashIdx >= 0 ? versionAndPlatform.slice(dashIdx + 1) : 'ruby';
    return { version, platform, yanked, dependenciesRaw, checksum };
  });
}

/** `/names`' body -> the gem names it lists (preamble stripped). */
export function parseNames(body: Buffer): string[] {
  const text = body.toString('utf8');
  return text
    .split('\n')
    .slice(2)
    .filter((l) => l.length > 0);
}

/** Bundler's `Settings.key_for(uri.host)` transform (`.` -> `__`, `-` -> `___`, upper-cased, `BUNDLE_`
 *  prefixed), confirmed live/H5 against a real `bundle install`: for this harness's own
 *  `localhost`/`127.0.0.1` targets this is always a plain `BUNDLE_LOCALHOST`/`BUNDLE_127__0__0__1`,
 *  but the general transform is implemented here rather than hardcoded so a remote host works too. */
export function bundleHostKey(host: string): string {
  const key = host.replace(/\./g, '__').replace(/-/g, '___').toUpperCase();
  return `BUNDLE_${key}`;
}

/** The value half of `BUNDLE_<HOSTKEY>=<user>:<secret>` for `credential` -- `undefined` for
 *  `anonymous` (the env var is left unset entirely, never set to an empty/bogus value). Values are
 *  used verbatim (never percent-encoded): this harness's own usernames/tokens never contain a `:`
 *  or other character Bundler's "please CGI escape" check rejects, confirmed live. */
export function bundleCredentialsValue(credential: MaterializedCredential): string | undefined {
  if (credential.transport !== 'basic') {
    return undefined;
  }
  return `${credential.username ?? ''}:${credential.password ?? ''}`;
}
