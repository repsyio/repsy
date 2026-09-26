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
 * Raw HTTP helpers for the Docker (Registry HTTP API V2 / OCI distribution) protocol, next to the
 * real-client adapter (`docker.ts`), built on `raw-http.ts`. Docker is the first protocol in this
 * harness with a genuine two-hop auth model -- a Bearer *token exchange* (`GET/POST /v2/token`) in
 * front of every operation request -- instead of Basic-per-request, so every helper here returns
 * which hop answered (`hop: 'token' | 'request'`), not just a bare status. Every fact below was read
 * from the server source first and then confirmed live (README.md's "Docker runner" section has the
 * H1-H14 probe evidence):
 *
 *  - Path shape: `/v2/<repoName>/<imageName>/...` (`DockerPathParser`, `repoName` lower-cased before
 *    lookup as a `RepoType.DOCKER` repo). `imageName` is ONE path segment (`[a-zA-Z0-9_\-]+`, no `/`
 *    -- unlike a real registry's multi-segment names), lower-case (Docker's own reference grammar
 *    requires it). `GET|HEAD /v2` or `/v2/` is the registry-level ping, no repo context.
 *  - Ping (`AbstractDockerRegistryCheckProtocolMethodHandler`): unauthenticated `GET /v2/` answers a
 *    bare `200`; any request WITHOUT a `Bearer ` `Authorization` header instead gets short-circuited
 *    by `DockerHeaderPreProcessor` (priority 50) into a `401` + `WWW-Authenticate: Bearer
 *    realm="<scheme>://<host[:port]>/v2/token",service="repsy",scope="repository:*:pull"` + an OCI
 *    `UNAUTHORIZED` body -- confirmed live: this is what a real client's own `GET /v2/` "ping" sees
 *    (its `scope` is a constant every real client, ggcr included, ignores in favour of its own).
 *  - Token endpoint (`AbstractDockerTokenProtocolMethodHandler`/`DockerAuthComponent
 *    .handleBearerAuth`): with `Authorization: Basic`, a deploy-token lookup by the password value
 *    runs FIRST regardless of username; not found falls through to username/password. The scope is
 *    NOT checked at issuance -- a read-only token, or a token of a different repo, still gets a JWT;
 *    authorization happens per OPERATION request instead (a second hop). Every failure here is a
 *    `401` + `WWW-Authenticate: Basic realm="Repsy"` + an OCI `UNAUTHORIZED` body naming the cause
 *    (RPS-1435: the generic `unAuthorized` text for a wrong password or an unknown user alike,
 *    `deployTokenExpired` for an expired deploy token, `unauthorizedRequest` when no credentials
 *    came with a push scope; it used to be empty, so the client printed `unauthorized: `). It
 *    still differs from an operation-hop 401 (Bearer challenge instead of Basic). Success: `{"token": "<jwt>", "access_token": "<jwt>", "expires_in": 1800, ...}` (both
 *    keys, `LoginResponse`).
 *  - Blob upload (`AbstractDockerUploadStartProtocolMethodHandler`/`...UploadFinalize...`): `POST
 *    .../blobs/uploads/` (permission WRITE) writes nothing at all (no DB row, no file) and answers
 *    `202` + an absolute `Location`; `PUT <location>?digest=sha256:<hex>` (monolithic, or after one or
 *    more `PATCH`es) verifies the digest (`400 DIGEST_INVALID` on a mismatch), stores a `Layer` row
 *    with `mediaType = DOCKER_LAYER` for EVERY blob including the config JSON (confirmed live: a
 *    HEAD's `Content-Type` is always `application/vnd.docker.image.rootfs.diff.tar.gzip`, whatever
 *    the blob actually is), and answers `201` + `Docker-Content-Digest`. Re-uploading a digest that
 *    already exists in this repo is accepted again (`201`, dedup at the storage layer).
 *  - Manifest push (`AbstractDockerManifestPushProtocolMethodHandler`/`AbstractDockerProtocolTxFacade
 *    .saveManifest`): the `Image` row is created BEFORE the override check runs. `!allowOverride` +
 *    an existing tag of this name -> `403` (`DENIED`/`packageOverrideDisabled`) -- confirmed live, and
 *    the blobs already uploaded for that refused push stay stored (protocol-inherent: blobs go up
 *    before the manifest, so a refused manifest never rolls them back). An unknown `Content-Type` ->
 *    a flat `500 UNKNOWN` (`IllegalArgumentException("unsupportedMediaType")` has no `ErrorHandler`
 *    mapping) -- confirmed live, RPS-1110. A config blob missing `os`/`architecture` -> the
 *    same flat `500` (`org.json`'s `getString` throwing inside `extractPlatform`) -- confirmed live,
 *    RPS-1116. Success: `201`, `Location` naming the manifest BY DIGEST, `Docker-Content-
 *    Digest`, empty body.
 *  - Manifest pull (`AbstractDockerManifestPullProtocolMethodHandler`): `GET` by tag or by
 *    `sha256:<hex>` both serve the exact stored bytes with the STORED media type as `Content-Type`
 *    (no charset suffix -- confirmed live) and `Docker-Content-Digest`. `AbstractDockerManifestCheck
 *    ProtocolMethodHandler` (`HEAD`) resolves a TAG name through the tag row same as GET, but a HEAD
 *    BY DIGEST answers `404` for a manifest a GET-by-digest serves fine (`findTagAndManifest` only
 *    ever looks up a tag row, never a digest) -- confirmed live, RPS-1215.
 *  - Overriding a tag makes the PREVIOUS manifest unpullable by digest: `GET
 *    manifests/sha256:<old digest>` after an accepted override answers `404` (the tag's one
 *    `Manifest` row is reused/overwritten in place, `ManifestTxService.updateManifestProperties`) --
 *    confirmed live, RPS-1216.
 *  - Blob check/pull (`AbstractDockerLayerCheckProtocolMethodHandler`/`...LayerPull...`): `HEAD`
 *    answers `200` with the row's own `Content-Type` (always `DOCKER_LAYER`, see above); `GET`
 *    answers `200` with a HARD-CODED `Content-Type: application/vnd.docker.container.image.v1+json`
 *    for EVERY blob (confirmed live: a layer blob's own GET reports the CONFIG media type) -- an
 *    observation, not filed (no real client reads a blob's `Content-Type` to decide what it is; the
 *    digest in the URL already says that).
 *  - Auth per operation (`DockerAuthPreProcessor`/`DockerAuthComponent.handleBearerAuth`): a deploy
 *    token's JWT is authorized only for ITS OWN repo and, for a write, only when `!readOnly` --
 *    confirmed live: a read-only token's own token-endpoint issuance still answers `200`, but its
 *    first WRITE request (`POST blobs/uploads/`) is refused `401` (Bearer challenge + OCI body), while
 *    a READ with the same JWT still succeeds. A user JWT (not a deploy token) is authorized for
 *    READ/WRITE on ANY repo (the RPS-939 model, same as every other protocol here).
 */
import { env } from '../env.js';
import { imageRef, registryHost, repoPath, v2RepoUrl, v2Url } from '../repo-url.js';
import { withBackoff429 } from '../scenarios/remote-throttle.js';
import type { Scenario } from '../scenarios/types.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import {
  adminCredential,
  authHeader,
  ociErrorOf,
  type RawResponse,
  sha256Hex,
} from './raw-http.js';

export { adminCredential, authHeader, ociErrorOf, sha256Hex, type RawResponse };
// The image reference, the registry host and the `/v2` URLs are built in `src/repo-url.ts`, which
// knows the URL scheme (`owner/repo`); re-exported so the docker specs keep importing them from here.
export { imageRef, registryHost, v2Url };

/** Every raw manifest GET/HEAD's `Accept` header -- the four types this protocol understands. */
export const MANIFEST_ACCEPT = [
  'application/vnd.docker.distribution.manifest.v2+json',
  'application/vnd.docker.distribution.manifest.list.v2+json',
  'application/vnd.oci.image.manifest.v1+json',
  'application/vnd.oci.image.index.v1+json',
].join(', ');

/** `e2e-<runid>-<slugified scenario id>` -- lower-case, one path segment, matches Repsy's own
 *  `IMAGE_NAME_PATTERN` (`[a-zA-Z0-9_\-]+`, no `/`) AND Docker's own (stricter) `[a-z0-9]+
 *  (?:[._-][a-z0-9]+)*`, and `ProtocolAdapter.packageName`'s signature. */
export function imageName(runId: string, scenario: Scenario): string {
  return `e2e-${runId}-${scenario.id.replace(/[^a-z0-9]/gi, '-').toLowerCase()}`;
}

/** Like `raw-http.ts`'s `withBackoff429Response`, but keeps the response `Headers` object too
 *  (`Location`/`Docker-Content-Digest`/`WWW-Authenticate`/... every helper below needs), which the
 *  shared helper's `RawResponse` shape does not carry. */
async function rawFetch(
  url: string,
  init: RequestInit,
): Promise<RawResponse & { headers: Headers }> {
  let last: (RawResponse & { headers: Headers }) | undefined;
  await withBackoff429(async () => {
    const res = await fetch(url, init);
    const bytes = Buffer.from(await res.arrayBuffer());
    last = { status: res.status, body: bytes, headers: res.headers };
    return last.status;
  });
  return last as RawResponse & { headers: Headers };
}

/** Raw, unauthenticated `GET /v2/` -- the registry ping every real client opens with. */
export async function rawPing(): Promise<RawResponse & { wwwAuthenticate?: string }> {
  const res = await rawFetch(v2Url('/'), {});
  return {
    status: res.status,
    body: res.body,
    wwwAuthenticate: res.headers.get('www-authenticate') ?? undefined,
  };
}

/** Parses a `WWW-Authenticate: Bearer realm="...",service="...",scope="..."` header value. */
export function parseBearerChallenge(header: string): {
  realm?: string;
  service?: string;
  scope?: string;
} {
  const result: { realm?: string; service?: string; scope?: string } = {};
  const re = /(\w+)="([^"]*)"/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(header)) !== null) {
    const [, key, value] = match;
    if (key === 'realm' || key === 'service' || key === 'scope') {
      result[key] = value;
    }
  }
  return result;
}

let cachedChallenge: { realm: string; service: string } | undefined;

/** The token endpoint's own `realm`/`service`, read once off a live `GET /v2/` ping and cached for
 *  the process -- never hard-coded, so a probe that asserts the realm's exact shape (R1) is checking
 *  the real thing, not restating a constant. */
async function challengeRealmAndService(): Promise<{ realm: string; service: string }> {
  if (cachedChallenge) {
    return cachedChallenge;
  }
  const ping = await rawPing();
  const parsed = parseBearerChallenge(ping.wwwAuthenticate ?? '');
  if (!parsed.realm || !parsed.service) {
    throw new Error(
      `docker adapter: GET /v2/ carried no usable Bearer challenge (got ${ping.status})`,
    );
  }
  cachedChallenge = { realm: parsed.realm, service: parsed.service };
  return cachedChallenge;
}

export function pushScope(repoName: string, image: string): string {
  return `repository:${repoPath(repoName)}/${image}:push,pull`;
}

export function pullScope(repoName: string, image: string): string {
  return `repository:${repoPath(repoName)}/${image}:pull`;
}

/** The scope that lets a token delete a manifest (RPS-1216, RPS-1434). Current `crane` asks for
 *  `push,pull,delete` up front and `skopeo` for `*`; older `crane` (v0.12 to v0.20) and `regctl` ask
 *  for `push,pull` first and add `delete` only after the registry's `insufficient_scope` challenge
 *  names this scope. */
export function deleteScope(repoName: string, image: string): string {
  return `repository:${repoPath(repoName)}/${image}:delete`;
}

export interface TokenResult {
  status: number;
  token?: string;
  wwwAuthenticate?: string;
  body: Buffer;
}

/** Raw `GET <realm>?service=<service>&scope=<scope>` (the token endpoint), with `credential`'s Basic
 *  header when it has one, no header at all for `anonymous` -- exactly what a real client's own
 *  bearer-transport does (`transport/bearer.go`). `realm`/`service` default to the live-probed
 *  challenge (`challengeRealmAndService`) unless overridden (R1's own assertion needs an explicit
 *  one to avoid the circular "probe the thing being asserted" shape). */
export async function rawToken(
  credential: MaterializedCredential,
  scope: string | undefined,
  opts?: { realm?: string; service?: string },
): Promise<TokenResult> {
  const { realm, service } =
    opts?.realm !== undefined && opts?.service !== undefined
      ? { realm: opts.realm, service: opts.service }
      : await challengeRealmAndService();
  const url = new URL(realm);
  url.searchParams.set('service', service);
  if (scope) {
    url.searchParams.set('scope', scope);
  }
  const res = await rawFetch(url.toString(), { headers: authHeader(credential) });
  let token: string | undefined;
  try {
    const parsed = JSON.parse(res.body.toString('utf8')) as { token?: unknown };
    token = typeof parsed.token === 'string' ? parsed.token : undefined;
  } catch {
    token = undefined;
  }
  return {
    status: res.status,
    token,
    wwwAuthenticate: res.headers.get('www-authenticate') ?? undefined,
    body: res.body,
  };
}

function bearerHeader(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

/**
 * The two-hop probe every operation goes through: a token exchange for `scope`, then `fn(bearer)`.
 * When the token hop itself does not answer `200` (an expired/revoked/wrong credential, or a scope a
 * real client's own request would be refused for), that is where a real client fails and this
 * returns the token hop's own response (`hop: 'token'`) without ever calling `fn`. Otherwise it
 * returns `fn`'s response (`hop: 'request'`). Every `RawResponse` this file returns for a `publish`/
 * `resolve`'s companion probe goes through this, so the loop still sees one plain `httpStatus`.
 */
export async function dockerRequest(
  credential: MaterializedCredential,
  scope: string,
  fn: (headers: Record<string, string>) => Promise<RawResponse & { headers: Headers }>,
): Promise<RawResponse & { headers: Headers; hop: 'token' | 'request' }> {
  const tokenRes = await rawToken(credential, scope);
  if (tokenRes.status !== 200 || !tokenRes.token) {
    return {
      status: tokenRes.status,
      body: tokenRes.body,
      headers: new Headers(
        tokenRes.wwwAuthenticate ? { 'www-authenticate': tokenRes.wwwAuthenticate } : {},
      ),
      hop: 'token',
    };
  }
  const res = await fn(bearerHeader(tokenRes.token));
  return { ...res, hop: 'request' };
}

/** `PUT /v2/<repo>/<image>/manifests/<ref>` -- exactly what a real client's manifest push sends
 *  (`remote/write.go`: `Content-Type: img.MediaType()`, body `img.RawManifest()`). Goes through the
 *  two-hop `dockerRequest` for `pushScope`. */
export async function rawPutManifest(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  ref: string,
  bytes: Buffer,
  contentType: string,
): Promise<RawResponse & { hop: 'token' | 'request'; location?: string; digestHeader?: string }> {
  const res = await dockerRequest(credential, pushScope(repoName, image), (headers) =>
    rawFetch(v2RepoUrl(repoName, `${image}/manifests/${ref}`), {
      method: 'PUT',
      headers: { ...headers, 'Content-Type': contentType },
      body: new Uint8Array(bytes),
    }),
  );
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    location: res.headers.get('location') ?? undefined,
    digestHeader: res.headers.get('docker-content-digest') ?? undefined,
  };
}

/** `GET /v2/<repo>/<image>/manifests/<ref>` with the standard multi-type `Accept`, through the
 *  two-hop `dockerRequest` for `pullScope`. */
export async function rawGetManifest(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  ref: string,
): Promise<
  RawResponse & { hop: 'token' | 'request'; digestHeader?: string; contentType?: string }
> {
  const res = await dockerRequest(credential, pullScope(repoName, image), (headers) =>
    rawFetch(v2RepoUrl(repoName, `${image}/manifests/${ref}`), {
      headers: { ...headers, Accept: MANIFEST_ACCEPT },
    }),
  );
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    digestHeader: res.headers.get('docker-content-digest') ?? undefined,
    contentType: res.headers.get('content-type') ?? undefined,
  };
}

/** `HEAD /v2/<repo>/<image>/manifests/<ref>` -- mirrors GET for both a tag and a digest reference
 *  (RPS-1215, fixed). */
export async function rawHeadManifest(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  ref: string,
): Promise<RawResponse & { hop: 'token' | 'request'; digestHeader?: string }> {
  const res = await dockerRequest(credential, pullScope(repoName, image), (headers) =>
    rawFetch(v2RepoUrl(repoName, `${image}/manifests/${ref}`), { method: 'HEAD', headers }),
  );
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    digestHeader: res.headers.get('docker-content-digest') ?? undefined,
  };
}

/** `DELETE /v2/<repo>/<image>/manifests/<ref>` (RPS-1216): a digest of either algorithm deletes the
 *  manifest and every tag that pointed at it, a tag deletes the tag only; `202` either way. Needs
 *  MANAGE, so it goes through the two-hop `dockerRequest` for `deleteScope`, unless `scope` says what
 *  the token is asked for instead (RPS-1434: a token asked for less than `delete` cannot delete). */
export async function rawDeleteManifest(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  ref: string,
  scope: string = deleteScope(repoName, image),
): Promise<RawResponse & { hop: 'token' | 'request'; wwwAuthenticate?: string }> {
  const res = await dockerRequest(credential, scope, (headers) =>
    rawFetch(v2RepoUrl(repoName, `${image}/manifests/${ref}`), { method: 'DELETE', headers }),
  );
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    wwwAuthenticate: res.headers.get('www-authenticate') ?? undefined,
  };
}

/** `HEAD /v2/<repo>/<image>/blobs/<digest>`. */
export async function rawHeadBlob(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  digest: string,
): Promise<
  RawResponse & { hop: 'token' | 'request'; contentLength?: string; contentType?: string }
> {
  const res = await dockerRequest(credential, pullScope(repoName, image), (headers) =>
    rawFetch(v2RepoUrl(repoName, `${image}/blobs/${digest}`), { method: 'HEAD', headers }),
  );
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    contentLength: res.headers.get('content-length') ?? undefined,
    contentType: res.headers.get('content-type') ?? undefined,
  };
}

/** `GET /v2/<repo>/<image>/blobs/<digest>`. */
export async function rawGetBlob(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  digest: string,
): Promise<RawResponse & { hop: 'token' | 'request'; contentType?: string }> {
  const res = await dockerRequest(credential, pullScope(repoName, image), (headers) =>
    rawFetch(v2RepoUrl(repoName, `${image}/blobs/${digest}`), { headers }),
  );
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    contentType: res.headers.get('content-type') ?? undefined,
  };
}

/** `POST /v2/<repo>/<image>/blobs/uploads/` alone -- writes nothing server-side (see this file's
 *  header), so this is the safe publish-side auth probe (`registry-rules.spec.ts`'s R3, and any
 *  probe that needs to exercise a WRITE permission check without actually storing a blob). */
export async function rawStartUpload(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
): Promise<RawResponse & { hop: 'token' | 'request'; location?: string }> {
  const res = await dockerRequest(credential, pushScope(repoName, image), (headers) =>
    rawFetch(v2RepoUrl(repoName, `${image}/blobs/uploads/`), { method: 'POST', headers }),
  );
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    location: res.headers.get('location') ?? undefined,
  };
}

/** `POST /v2/<repo>/<image>/blobs/uploads/` with a token the caller already holds (no token hop):
 *  the probe of what a stored `/v2/token` JWT still opens (RPS-1552). `202` for a live token, `401`
 *  for one the server no longer accepts. Writes nothing, like `rawStartUpload`. */
export async function rawStartUploadWithToken(
  repoName: string,
  image: string,
  token: string,
): Promise<RawResponse> {
  return rawFetch(v2Url(`/${repoName}/${image}/blobs/uploads/`), {
    method: 'POST',
    headers: bearerHeader(token),
  });
}

/**
 * Uploads one blob start-to-finish: `POST` to start, then either one `PATCH` (`mode: 'patch'`) or
 * nothing (`mode: 'monolithic'`, the default) before the final `PUT ?digest=`. Mirrors ggcr's own
 * `remote/write.go` (one `PATCH` streaming the whole blob, or a bare monolithic `PUT`).
 */
export async function rawUploadBlob(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  bytes: Buffer,
  digest: string,
  opts?: { mode?: 'monolithic' | 'patch' },
): Promise<RawResponse & { hop: 'token' | 'request'; digestHeader?: string }> {
  const res = await dockerRequest(credential, pushScope(repoName, image), async (headers) => {
    const startRes = await rawFetch(v2RepoUrl(repoName, `${image}/blobs/uploads/`), {
      method: 'POST',
      headers,
    });
    if (startRes.status !== 202) {
      return startRes;
    }
    let location = startRes.headers.get('location');
    if (!location) {
      return startRes;
    }

    if (opts?.mode === 'patch') {
      const patchRes = await rawFetch(new URL(location, env.repoBaseUrl).toString(), {
        method: 'PATCH',
        headers: { ...headers, 'Content-Type': 'application/octet-stream' },
        body: new Uint8Array(bytes),
      });
      location = patchRes.headers.get('location') ?? location;
    }

    const finalizeUrl = new URL(location, env.repoBaseUrl);
    finalizeUrl.searchParams.set('digest', digest);
    const body = opts?.mode === 'patch' ? undefined : new Uint8Array(bytes);
    return rawFetch(finalizeUrl.toString(), {
      method: 'PUT',
      headers: body ? { ...headers, 'Content-Type': 'application/octet-stream' } : headers,
      body,
    });
  });
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    digestHeader: res.headers.get('docker-content-digest') ?? undefined,
  };
}

// ---------------------------------------------------------------------------------------------
// RPS-1478 part A: the listing endpoints and the `mount=` fallback (`tests/docker/registry-api.spec.ts`)
// ---------------------------------------------------------------------------------------------

/** The scope `skopeo`/`crane catalog`/`regctl repo ls` ask a `GET /v2/_catalog` token for. The
 *  server never reads it (the route does not exist), it is only what a real client would send. */
export const CATALOG_SCOPE = 'registry:catalog:*';

/** A bare GET/HEAD of `/v2<pathSuffix>` with NO `Authorization` header at all (what a client that
 *  skipped the ping-and-token dance would send). Keeps the challenge header so a test can say whether
 *  the server answered with a `Bearer` challenge (a known route) or not (an unknown one). */
export async function rawGetAnonymous(
  pathSuffix: string,
  method: 'GET' | 'HEAD' = 'GET',
): Promise<RawResponse & { hop: 'request'; wwwAuthenticate?: string }> {
  const res = await rawFetch(v2Url(pathSuffix), { method });
  return {
    status: res.status,
    body: res.body,
    hop: 'request',
    wwwAuthenticate: res.headers.get('www-authenticate') ?? undefined,
  };
}

/** `GET /v2/<repo>/<image>/tags/list[?query]` through the two-hop `dockerRequest` for `pullScope`
 *  (what `crane ls`, `skopeo list-tags` and `regctl tag ls` send). `query` is appended as given
 *  (`n=1&last=x` is the pagination a real client may add). */
export async function rawTagsList(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  query?: string,
): Promise<RawResponse & { hop: 'token' | 'request'; contentType?: string }> {
  const rel = `${image}/tags/list${query ? `?${query}` : ''}`;
  const res = await dockerRequest(credential, pullScope(repoName, image), (headers) =>
    rawFetch(v2RepoUrl(repoName, rel), { headers }),
  );
  return {
    status: res.status,
    body: res.body,
    hop: res.hop,
    contentType: res.headers.get('content-type') ?? undefined,
  };
}

/** `GET /v2/_catalog[?query]` through the two-hop `dockerRequest` for `CATALOG_SCOPE`. */
export async function rawCatalog(
  credential: MaterializedCredential,
  query?: string,
): Promise<RawResponse & { hop: 'token' | 'request' }> {
  const res = await dockerRequest(credential, CATALOG_SCOPE, (headers) =>
    rawFetch(v2Url(`/_catalog${query ? `?${query}` : ''}`), { headers }),
  );
  return { status: res.status, body: res.body, hop: res.hop };
}

/** `GET /v2/<repo>/<image>/referrers/<digest>[?artifactType=...]` (OCI distribution 1.1's referrers
 *  API, what `oras discover`, `regctl artifact tree` and `cosign tree` ask first) through the
 *  two-hop `dockerRequest` for `pullScope`. */
export async function rawReferrers(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  digest: string,
  artifactType?: string,
): Promise<RawResponse & { hop: 'token' | 'request' }> {
  const rel = `${image}/referrers/${digest}${
    artifactType ? `?artifactType=${encodeURIComponent(artifactType)}` : ''
  }`;
  const res = await dockerRequest(credential, pullScope(repoName, image), (headers) =>
    rawFetch(v2RepoUrl(repoName, rel), { headers }),
  );
  return { status: res.status, body: res.body, hop: res.hop };
}

export interface MountUploadResult {
  /** The answer to `POST .../blobs/uploads/?mount=<digest>[&from=<repo>]`. */
  status: number;
  hop: 'token' | 'request';
  body: Buffer;
  /** The `Location` of the upload session (absent when the POST did not answer 202). */
  location?: string;
  /** `Docker-Upload-UUID` of the upload session. */
  uploadUuid?: string;
  /** Present when `bytes` was given and the POST answered 202: the `PUT <location>?digest=` after it. */
  finalizeStatus?: number;
  finalizeDigest?: string;
}

/**
 * `POST /v2/<repo>/<image>/blobs/uploads/?mount=<digest>[&from=<fromRepo>]` (the cross-repository
 * blob mount `docker push`, `skopeo copy` and `regctl image copy` attempt when the source and the
 * destination live in one registry), and, when `bytes` is given and the POST answered `202` (the
 * distribution spec's "mount not honoured, here is an upload session" fallback), the monolithic `PUT
 * <location>?digest=` a client then sends -- all in ONE token hop. The token asks for the push scope
 * of the destination only (a real client adds a pull scope for the source repository too; the
 * token endpoint takes one `scope` here and the server never reads `from`, see the spec's header).
 */
export async function rawMountUpload(
  repoName: string,
  credential: MaterializedCredential,
  image: string,
  mount: { digest: string; fromRepo?: string; fromImage?: string },
  bytes?: Buffer,
): Promise<MountUploadResult> {
  const from =
    mount.fromRepo !== undefined && mount.fromImage !== undefined
      ? `${mount.fromRepo}/${mount.fromImage}`
      : mount.fromRepo;
  const query = `mount=${encodeURIComponent(mount.digest)}${
    from !== undefined ? `&from=${encodeURIComponent(from)}` : ''
  }`;
  let finalize: (RawResponse & { headers: Headers }) | undefined;
  let start: (RawResponse & { headers: Headers }) | undefined;
  const res = await dockerRequest(credential, pushScope(repoName, image), async (headers) => {
    start = await rawFetch(v2RepoUrl(repoName, `${image}/blobs/uploads/?${query}`), {
      method: 'POST',
      headers,
    });
    const location = start.headers.get('location');
    if (bytes !== undefined && start.status === 202 && location) {
      const url = new URL(location, env.repoBaseUrl);
      url.searchParams.set('digest', mount.digest);
      finalize = await rawFetch(url.toString(), {
        method: 'PUT',
        headers: { ...headers, 'Content-Type': 'application/octet-stream' },
        body: new Uint8Array(bytes),
      });
    }
    return start;
  });
  return {
    status: res.status,
    hop: res.hop,
    body: res.body,
    location: start?.headers.get('location') ?? undefined,
    uploadUuid: start?.headers.get('docker-upload-uuid') ?? undefined,
    finalizeStatus: finalize?.status,
    finalizeDigest: finalize?.headers.get('docker-content-digest') ?? undefined,
  };
}
