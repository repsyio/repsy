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
 * The one place a repository's protocol URL is built (RPS-1492, E2E-02). Repsy OS serves a repository
 * at `/<repo>/...`; an owner-scoped registry such as Repsy Cloud serves it at `/<owner>/<repo>/...`
 * (Docker: `/v2/<owner>/<repo>/<image>`). Which one this run talks to is `target.urlScheme`
 * (`REPSY_E2E_URL_SCHEME`, default `repo`) and, for `owner-repo`, `env.repoOwner` (`REPSY_REPO_OWNER`),
 * so a client, a raw probe or a spec never writes `${env.repoBaseUrl}/${repoName}` itself.
 *
 * Both are read at call time, not at import, so a unit test can switch scheme and owner on the shared
 * `env`/`target` objects (`tests/skeleton/repo-url.spec.ts`).
 *
 * What is NOT repo-scoped keeps using `env.repoBaseUrl` directly: the host itself (`new URL(env
 * .repoBaseUrl).host`, protocol, hostname), `/v2/` and `/v2/token` (through `v2Url`), and the URLs a
 * server hands back (`Location`) resolved against it.
 */
import { env } from './env.js';
import { target } from './target.js';

/**
 * The repository's path segment(s): `<repo>` on the `repo` scheme, `<owner>/<repo>` on `owner-repo`.
 * Use it where only the path is needed (a token scope, `<host>/<path>` in an npmrc or GOPROXY), and
 * `repoUrl` for a whole URL.
 */
export function repoPath(name: string): string {
  if (target.urlScheme === 'repo') {
    return name;
  }
  if (!env.repoOwner) {
    throw new Error(
      'urlScheme is owner-repo but REPSY_REPO_OWNER is not set: an owner-scoped URL needs its owner.',
    );
  }
  return `${env.repoOwner}/${name}`;
}

/**
 * `<repoBaseUrl>/<repoPath(name)>`, plus `/<rel>` when `rel` is given. `rel` is relative to the
 * repository root, with no leading slash: `repoUrl('r', 'v3/index.json')`. Note the difference
 * between no `rel` (`.../r`, no trailing slash) and `''` (`.../r/`, the trailing slash that cargo,
 * npm, PyPI and NuGet registry URLs need).
 */
export function repoUrl(name: string, rel?: string): string {
  const base = `${env.repoBaseUrl}/${repoPath(name)}`;
  return rel === undefined ? base : `${base}/${rel}`;
}

/** `<host>[:<port>]` of the protocol port: the first segment of every Docker/Helm OCI reference. */
export function registryHost(): string {
  return new URL(env.repoBaseUrl).host;
}

/**
 * `<host>/<repoPath(repoName)>/<image>:<tag>`: a Docker (or OCI) image reference. It has no scheme,
 * so it is what `docker`, `crane`, `skopeo`, `regctl` and `oras` are given.
 */
export function imageRef(repoName: string, image: string, tag: string): string {
  return `${registryHost()}/${repoPath(repoName)}/${image}:${tag}`;
}

/**
 * `<repoBaseUrl>/v2<pathSuffix>` for a registry-wide path (`/`, `/_catalog`, `/token`), which names
 * no repository. `pathSuffix` starts with `/`.
 */
export function v2Url(pathSuffix: string): string {
  return `${env.repoBaseUrl}/v2${pathSuffix}`;
}

/**
 * `<repoBaseUrl>/v2/<repoPath(repoName)>/<rel>`: the registry API URL of one image or chart of a
 * repository, `rel` being `<image>/manifests/<ref>`, `<image>/blobs/<digest>` and so on.
 */
export function v2RepoUrl(repoName: string, rel: string): string {
  return v2Url(`/${repoPath(repoName)}/${rel}`);
}
