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
 * Raw HTTP helpers for BOTH Helm protocols Repsy implements on the same protocol port (step 4b,
 * RPS-294): the OCI distribution-spec routes (`/v2/<repo>/<chart>/...`) and the ChartMuseum-style
 * classic routes (`GET /<repo>/index.yaml`, `GET /<repo>/charts/<file>.tgz`,
 * `POST /<repo>/api/charts` / `POST /api/<repo>/charts`, `DELETE /<repo>/api/charts/<name>/<version>`).
 * Built on `raw-http.ts`, next to the real-client adapters (`helm.ts` for OCI, `helm-classic.ts`
 * for classic). Every fact below was read from the server source first and then confirmed live
 * against a running instance (this story's own H1-H18 probes; see README.md's "Helm runner"
 * section for the write-up):
 *
 *  - Auth is SINGLE-HOP Basic for both modes (`HelmHeaderPreProcessor` + `HelmAuthPreProcessor`,
 *    priority 50/100): no missing `Authorization` header, or a wrong one, on a route that needs
 *    one -> a bare `401` + `WWW-Authenticate: Basic realm="Repsy"` -- an OCI JSON envelope
 *    (`OciErrorBodyAdvice`, RPS-1039) on a `/v2/...` path, no body at all on a classic path
 *    (confirmed live: `GET /<repo>/index.yaml` without credentials on a private repo answers a
 *    bodyless 401). Unlike Docker, there is NO Bearer token exchange anywhere in Helm's own
 *    handlers -- `docker`'s two-hop `dockerRequest` pattern does not apply here.
 *  - `GET|HEAD /v2/` (the ping `helm registry login`/`crane`'s own probe open with) is answered by
 *    the DOCKER provider, not Helm's own (dead-code) version-check handler: `ProtocolRouterController
 *    .route` tries every registered handler in provider-list order (maven, pypi, npm, docker, golang,
 *    cargo, helm, nuget, ruby -- `repsy-backend/pom.xml`), and Docker's `DockerPathParser` matches
 *    `/v2/` first. Confirmed live (H1, this file's `rawPing` re-export from `docker-raw.ts`): a real
 *    `helm registry login <host> --plain-http` is steered to DOCKER's `/v2/token` token endpoint, not
 *    to any Helm-specific check.
 *  - **RPS-1220 (fixed)**: `helm registry login` used to SUCCEED with a WRONG password. Docker's
 *    token endpoint answered the ping's own wildcard-pull-scope OAuth2 form POST (no `Authorization`
 *    header at all -- oras-go's `ForceAttemptOAuth2` path) with an ANONYMOUS token, 200, before any
 *    credential was ever checked; `helm registry login` treated that 200 as success and wrote the
 *    (wrong) credential into `HELM_REGISTRY_CONFIG` regardless. The Docker token handler now
 *    recognises a `grant_type=password` form body with no `Authorization` header and validates it
 *    through the same credential check the Basic-header path uses, so a wrong password now answers
 *    `401` and `helm registry login` genuinely fails. This was a Docker-provider bug that surfaced
 *    through Helm's login flow, not something in Helm's own auth code -- see `docker-raw.ts`'s own
 *    header for the token endpoint's behaviour in detail.
 *  - OCI blob upload (`AbstractHelmOciBlobUploadStart/Chunk/FinalizeProtocolMethodHandler`): `POST
 *    .../blobs/uploads/` writes nothing at all and answers `202` + an absolute `Location` +
 *    `Docker-Upload-UUID`; a `PATCH` appends and answers `202` + `Range`; the finalizing
 *    `PUT ...?digest=sha256:<hex>` verifies the digest (`400 digestMismatch` -> OCI `DIGEST_INVALID`
 *    on a mismatch; a non-sha256 digest -> `400 blobDigestUnsupported`), stores the request's own
 *    `Content-Type` (or `application/octet-stream` when absent/oversized) against the blob, and
 *    answers `201` + `Docker-Content-Digest`. Re-uploading an existing digest is accepted again
 *    (dedup at the storage layer, confirmed live).
 *  - OCI blob check/pull (`.../blobs/<digest>`): blobs are keyed by `(repo, digest)` ONLY -- the
 *    `<name>` path segment is ignored for a blob lookup (confirmed live: a blob uploaded under one
 *    chart name is HEAD/GET-able under any other chart name of the same repo).
 *  - OCI manifest push (`AbstractHelmOciManifestPushProtocolMethodHandler`): no `Content-Type` ->
 *    a bare, bodyless `400` (**B-H7 candidate**, confirmed live: no OCI envelope despite RPS-1039);
 *    the override check (`ItemAlreadyExistException("chartAlreadyExists")` when the
 *    `(name, reference)` coordinate already exists and `!allowOverride`) runs BEFORE the request body
 *    is read, mapped to `409` with OCI code `DENIED` and `detail: "chartAlreadyExists"` -- confirmed
 *    live for both a fresh push under `allowOverride:false` and a real `helm push`/`cm-push`
 *    re-attempt (both real clients exit non-zero on it). **B-H2 (candidate)**, confirmed live: an
 *    ACCEPTED override (`allowOverride:true`) that changes the chart's bytes updates the manifest row
 *    in place but leaves `index.yaml`'s `digest` field for that (name, version) at the OLD layer
 *    digest -- the classic index keeps advertising stale content for an OCI-overridden chart.
 *  - OCI manifest pull: looks up ONLY the exact `(repo, name, reference)` a manifest was pushed
 *    under -- a manifest pushed by TAG is not resolvable by digest (GET/HEAD both `404`
 *    `MANIFEST_UNKNOWN`), unlike Docker (RPS-1215 is HEAD-only there).
 *  - **B-H3** (fixed by RPS-1219): `GET /v2/<repo>/<name>/tags/list` used to have no handler at all
 *    (`404` with OCI code `NAME_UNKNOWN`, msgId `unknownPath`), so a real `helm pull
 *    oci://.../<chart>` WITHOUT an exact `--version` (Helm's own `ValidateReference` calls `Tags(...)`
 *    whenever the version is empty or a semver CONSTRAINT) failed outright. It is served now (`200`).
 *  - **B-H1 (candidate)**, confirmed live cleanly (a chart name that NEVER touched the classic
 *    route): `index.yaml` (`generateIndex`) lists every `helm_chart_version` row of the repo,
 *    INCLUDING ones that only ever went through the OCI route, at `charts/<name>-<version>.tgz` --
 *    but the classic download handler (`getChart`) only ever reads the classic storage path, which an
 *    OCI-only publish never wrote, so `GET charts/<name>-<version>.tgz` (and therefore a real
 *    `helm pull --repo <url> <chart> --version <v>` / `helm repo add` + `helm install`) 404s
 *    (`chartNotFound`) for a chart that was only ever pushed via `helm push oci://`.
 *  - Classic push (`AbstractHelmChartPushProtocolMethodHandler`/`AbstractHelmProtocolTxFacade
 *    .pushChart`): a missing `chart` multipart part -> `400` with a PLAIN-TEXT body
 *    `Missing 'chart' part` (no JSON envelope at all, confirmed live); the override check runs BEFORE
 *    any write (`ItemAlreadyExistException("chartAlreadyExists")` -> `409` when
 *    `!allowOverride` and the coordinate already exists -- confirmed live, nothing is stored on the
 *    refusal); `?force=true` (what `helm cm-push --force` sends) is never read server-side --
 *    confirmed live: `cm-push --force` against an `allowOverride:false` repo still gets the same
 *    `409` the plain push does. TWO routes accept the exact same request:
 *    `POST /<repo>/api/charts` (`HelmServerPathParser`, ChartMuseum's own historical route) and
 *    `POST /api/<repo>/charts` (`HelmChartMuseumPathParser`, what the real `helm cm-push` plugin
 *    actually builds -- confirmed live).
 *  - Classic index (`GET /<repo>/index.yaml`): `digest` carries a `sha256:` PREFIX (**B-H8
 *    candidate**, observation only -- `helm repo index` itself emits bare hex, but neither `helm
 *    repo update`/`pull`/`install` verify it against anything, so this is low impact).
 *  - Classic download (`GET /<repo>/charts/<file>.tgz`): `404 chartNotFound` when absent,
 *    `Content-Type: application/octet-stream`.
 */
import { parse as parseYaml } from 'yaml';

import { env } from '../env.js';
import { registryHost, repoPath, repoUrl, v2RepoUrl } from '../repo-url.js';
import { withBackoff429 } from '../scenarios/remote-throttle.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import {
  adminCredential,
  authHeader,
  msgIdOf,
  ociErrorOf,
  type RawResponse,
  sha256Hex,
} from './raw-http.js';

export { adminCredential, authHeader, msgIdOf, ociErrorOf, sha256Hex, type RawResponse };
// The registry host is derived in `src/repo-url.ts` with every other URL; re-exported for the helm specs.
export { registryHost };
// The `/v2/` ping and its Bearer-challenge parser are Docker's own (see this file's header): reused
// here, never reimplemented, so a probe that reads them is checking the one real thing.
export { rawPing, parseBearerChallenge } from './docker-raw.js';

/** Every raw manifest GET/HEAD's `Accept` -- Helm only ever produces the OCI manifest media type. */
export const MANIFEST_ACCEPT = 'application/vnd.oci.image.manifest.v1+json';

/** The four Helm-specific OCI media types (`pkg/registry/client.go`). */
export const HELM_MEDIA_TYPES = {
  manifest: 'application/vnd.oci.image.manifest.v1+json',
  config: 'application/vnd.cncf.helm.config.v1+json',
  layer: 'application/vnd.cncf.helm.chart.content.v1.tar+gzip',
  prov: 'application/vnd.cncf.helm.chart.provenance.v1.prov',
} as const;

/** `e2e-<runid>-<slugified scenario id>` -- lower-case, matches Repsy's own chart-name pattern
 *  (`^[a-z0-9][a-z0-9-]*$`), Helm's own chart-name rule and the OCI path-component grammar. */
export function chartName(runId: string, scenario: Scenario): string {
  return `e2e-${runId}-${scenario.id.replace(/[^a-z0-9]/gi, '-').toLowerCase()}`;
}

export function chartFileName(name: string, version: string): string {
  return `${name}-${version}.tgz`;
}

export function ociRepoRef(repoName: string): string {
  return `oci://${registryHost()}/${repoPath(repoName)}`;
}

export function ociChartRef(repoName: string, chart: string): string {
  return `${ociRepoRef(repoName)}/${chart}`;
}

export function classicRepoUrl(repoName: string): string {
  return repoUrl(repoName);
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

// ---------------------------------------------------------------------------------------------
// OCI routes
// ---------------------------------------------------------------------------------------------

export interface OciResponse extends RawResponse {
  location?: string;
  digestHeader?: string;
  contentType?: string;
  wwwAuthenticate?: string;
}

function toOciResponse(res: RawResponse & { headers: Headers }): OciResponse {
  return {
    status: res.status,
    body: res.body,
    msgId: res.msgId,
    location: res.headers.get('location') ?? undefined,
    digestHeader: res.headers.get('docker-content-digest') ?? undefined,
    contentType: res.headers.get('content-type') ?? undefined,
    wwwAuthenticate: res.headers.get('www-authenticate') ?? undefined,
  };
}

/** `PUT /v2/<repo>/<chart>/manifests/<reference>` -- exactly what a real `helm push`'s final
 *  request sends (`pkg/registry/client.go`'s `oras.ExtendedCopy`, PUT by tag). An empty/undefined
 *  `contentType` OMITS the header entirely (not an empty `Content-Type:` value, which the server
 *  treats differently -- confirmed live: only a truly ABSENT header trips the bare-400 "no
 *  Content-Type" rule; an empty string still lets the request past that check). */
export async function rawPutManifest(
  repoName: string,
  credential: MaterializedCredential,
  chart: string,
  reference: string,
  bytes: Buffer,
  contentType?: string,
): Promise<OciResponse> {
  const res = await rawFetch(v2RepoUrl(repoName, `${chart}/manifests/${reference}`), {
    method: 'PUT',
    headers: { ...authHeader(credential), ...(contentType ? { 'Content-Type': contentType } : {}) },
    body: new Uint8Array(bytes),
  });
  return toOciResponse(res);
}

export async function rawGetManifest(
  repoName: string,
  credential: MaterializedCredential,
  chart: string,
  reference: string,
): Promise<OciResponse> {
  const res = await rawFetch(v2RepoUrl(repoName, `${chart}/manifests/${reference}`), {
    headers: { ...authHeader(credential), Accept: MANIFEST_ACCEPT },
  });
  return toOciResponse(res);
}

export async function rawHeadManifest(
  repoName: string,
  credential: MaterializedCredential,
  chart: string,
  reference: string,
): Promise<OciResponse> {
  const res = await rawFetch(v2RepoUrl(repoName, `${chart}/manifests/${reference}`), {
    method: 'HEAD',
    headers: authHeader(credential),
  });
  return toOciResponse(res);
}

export async function rawHeadBlob(
  repoName: string,
  credential: MaterializedCredential,
  chart: string,
  digest: string,
): Promise<OciResponse> {
  const res = await rawFetch(v2RepoUrl(repoName, `${chart}/blobs/${digest}`), {
    method: 'HEAD',
    headers: authHeader(credential),
  });
  return toOciResponse(res);
}

export async function rawGetBlob(
  repoName: string,
  credential: MaterializedCredential,
  chart: string,
  digest: string,
): Promise<OciResponse> {
  const res = await rawFetch(v2RepoUrl(repoName, `${chart}/blobs/${digest}`), {
    headers: authHeader(credential),
  });
  return toOciResponse(res);
}

/** `POST /v2/<repo>/<chart>/blobs/uploads/` alone -- writes nothing server-side, so this is the
 *  safe publish-side auth probe (mirrors `docker-raw.ts`'s `rawStartUpload`). */
export async function rawStartUpload(
  repoName: string,
  credential: MaterializedCredential,
  chart: string,
): Promise<OciResponse> {
  const res = await rawFetch(v2RepoUrl(repoName, `${chart}/blobs/uploads/`), {
    method: 'POST',
    headers: authHeader(credential),
  });
  return toOciResponse(res);
}

/** Uploads one blob start-to-finish: `POST` to start, then either one `PATCH`
 *  (`mode: 'patch'`) or nothing (`mode: 'monolithic'`, the default) before the final
 *  `PUT ?digest=`. */
export async function rawUploadBlob(
  repoName: string,
  credential: MaterializedCredential,
  chart: string,
  bytes: Buffer,
  digest: string,
  opts?: { mode?: 'monolithic' | 'patch'; contentType?: string },
): Promise<OciResponse> {
  const headers = authHeader(credential);
  const startRes = await rawFetch(v2RepoUrl(repoName, `${chart}/blobs/uploads/`), {
    method: 'POST',
    headers,
  });
  if (startRes.status !== 202) {
    return toOciResponse(startRes);
  }
  let location = startRes.headers.get('location');
  if (!location) {
    return toOciResponse(startRes);
  }

  if (opts?.mode === 'patch') {
    const patchRes = await rawFetch(new URL(location, env.repoBaseUrl).toString(), {
      method: 'PATCH',
      headers: { ...headers, 'Content-Type': opts?.contentType ?? 'application/octet-stream' },
      body: new Uint8Array(bytes),
    });
    location = patchRes.headers.get('location') ?? location;
  }

  const finalizeUrl = new URL(location, env.repoBaseUrl);
  finalizeUrl.searchParams.set('digest', digest);
  const body = opts?.mode === 'patch' ? undefined : new Uint8Array(bytes);
  const finalRes = await rawFetch(finalizeUrl.toString(), {
    method: 'PUT',
    headers: body
      ? { ...headers, 'Content-Type': opts?.contentType ?? 'application/octet-stream' }
      : headers,
    body,
  });
  return toOciResponse(finalRes);
}

/** `GET /v2/<repo>/<chart>/tags/list` -- pins **B-H3** (fixed by RPS-1219): the route is served (`200`). */
export async function rawGetTagsList(
  repoName: string,
  credential: MaterializedCredential,
  chart: string,
): Promise<OciResponse> {
  const res = await rawFetch(v2RepoUrl(repoName, `${chart}/tags/list`), {
    headers: authHeader(credential),
  });
  return toOciResponse(res);
}

// ---------------------------------------------------------------------------------------------
// Classic (ChartMuseum-style) routes
// ---------------------------------------------------------------------------------------------

export interface IndexEntry {
  name: string;
  version: string;
  digest?: string;
  urls: string[];
  description?: string;
  appVersion?: string;
  type?: string;
  created?: string;
}

export interface ParsedIndex {
  apiVersion?: string;
  entries: Record<string, IndexEntry[]>;
  generated?: string;
}

/** Parses `index.yaml` with the real `yaml` package (added for this step, `pnpm add yaml`) rather
 *  than a hand-rolled reader: the shape `HelmIndexGenerator` produces is small but this is a real
 *  YAML document (quoted/unquoted scalars, ISO instants), and a real parser is the safer choice --
 *  mirrors every field `HelmIndexEntryDto` writes (`name`, `version`, `digest`, `urls`,
 *  `description?`, `appVersion?`, `type?`, `created`). */
export function parseIndex(yamlText: string): ParsedIndex {
  const doc = parseYaml(yamlText) as {
    apiVersion?: unknown;
    generated?: unknown;
    entries?: Record<string, unknown>;
  };
  const entries: Record<string, IndexEntry[]> = {};
  for (const [name, rawList] of Object.entries(doc.entries ?? {})) {
    if (!Array.isArray(rawList)) {
      continue;
    }
    entries[name] = rawList.map((raw) => {
      const e = raw as Record<string, unknown>;
      return {
        name: typeof e.name === 'string' ? e.name : name,
        version: String(e.version),
        digest: typeof e.digest === 'string' ? e.digest : undefined,
        urls: Array.isArray(e.urls) ? (e.urls as string[]) : [],
        description: typeof e.description === 'string' ? e.description : undefined,
        appVersion: typeof e.appVersion === 'string' ? e.appVersion : undefined,
        type: typeof e.type === 'string' ? e.type : undefined,
        created: typeof e.created === 'string' ? e.created : undefined,
      };
    });
  }
  return {
    apiVersion: typeof doc.apiVersion === 'string' ? doc.apiVersion : undefined,
    generated: typeof doc.generated === 'string' ? doc.generated : undefined,
    entries,
  };
}

export function indexEntry(
  index: ParsedIndex,
  name: string,
  version: string,
): IndexEntry | undefined {
  return index.entries[name]?.find((e) => e.version === version);
}

export async function rawGetIndex(
  repoName: string,
  credential: MaterializedCredential,
): Promise<RawResponse> {
  return rawFetch(`${classicRepoUrl(repoName)}/index.yaml`, { headers: authHeader(credential) });
}

/**
 * Raw multipart POST of a classic chart publish -- exactly the request the `cm-push` plugin
 * itself builds (`pkg/chartmuseum/upload.go`'s `setUploadChartPackageRequestBody`: one part named
 * `chart`, the file's raw bytes, `multipart/form-data`; never sets `Content-Type` by hand here
 * either -- `fetch`'s own `FormData` adds the boundary). `route: 'chartmuseum'` hits
 * `POST /<repo>/api/charts` (`HelmServerPathParser`); `route: 'plugin'` (the default) hits
 * `POST /api/<repo>/charts`, what a real `helm cm-push` sends. `force` appends `?force=true`
 * (`cm-push --force`'s own query param) -- confirmed live (H-C2) that Repsy never reads it.
 */
export async function rawUploadChart(
  repoName: string,
  credential: MaterializedCredential,
  tgzBytes: Buffer,
  fileName: string,
  opts?: { route?: 'chartmuseum' | 'plugin'; force?: boolean },
): Promise<RawResponse> {
  const base =
    opts?.route === 'chartmuseum'
      ? `${classicRepoUrl(repoName)}/api/charts`
      : `${env.repoBaseUrl}/api/${repoPath(repoName)}/charts`;
  const url = opts?.force ? `${base}?force=true` : base;
  const form = new FormData();
  form.append('chart', new Blob([new Uint8Array(tgzBytes)]), fileName);
  return rawFetch(url, { method: 'POST', headers: authHeader(credential), body: form });
}

/** Raw multipart POST with NO `chart` part at all -- pins the classic push handler's plain-text
 *  `Missing 'chart' part` `400` (confirmed live). */
export async function rawUploadChartMissingPart(
  repoName: string,
  credential: MaterializedCredential,
): Promise<RawResponse> {
  const form = new FormData();
  form.append('notchart', new Blob([new Uint8Array(Buffer.from('x'))]), 'x.txt');
  return rawFetch(`${env.repoBaseUrl}/api/${repoPath(repoName)}/charts`, {
    method: 'POST',
    headers: authHeader(credential),
    body: form,
  });
}

export async function rawDownloadChart(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
  version: string,
): Promise<RawResponse> {
  return rawFetch(`${classicRepoUrl(repoName)}/charts/${chartFileName(name, version)}`, {
    headers: authHeader(credential),
  });
}

export async function rawDeleteChart(
  repoName: string,
  credential: MaterializedCredential,
  name: string,
  version: string,
): Promise<RawResponse> {
  return rawFetch(`${classicRepoUrl(repoName)}/api/charts/${name}/${version}`, {
    method: 'DELETE',
    headers: authHeader(credential),
  });
}
