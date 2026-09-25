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
 * Raw HTTP helpers for the maven repository protocol, next to the real-client adapter
 * (`maven.ts`): PUT/GET of single files, a directory-listing walk that fingerprints a whole repo,
 * and builders/parsers for the two `maven-metadata.xml` shapes a deploy writes. They exist for two
 * jobs `mvn` cannot do: pin the exact HTTP status a real client hides behind its exit code, and
 * look at what a (refused) deploy did or did not leave in the repository, always with the admin
 * credential so the look never depends on the scenario's own (often deliberately broken) one.
 *
 * The protocol-agnostic pieces (`RawResponse`, the admin credential, the Basic `Authorization`
 * header builder, `sha256Hex`, the 429-backoff response wrapper) moved to `clients/raw-http.ts`
 * (step 3a, RPS-294), so a second protocol's own raw-HTTP client (`npm-raw.ts`) does not have to
 * depend on this maven-specific module; they are re-exported here under their original names so
 * every existing import of this file keeps working unchanged.
 *
 * Layout facts these helpers rely on, all probed against a running instance:
 *  - a directory (`g/a/`, `g/a/<version>/`) answers 200 with an HTML listing of `<a href>` entries
 *    (sub-directories end in `/`, the parent link is `../`) and 404 when nothing lives under it, so
 *    "nothing was stored" is simply a 404 on the directory;
 *  - a snapshot deploy writes a version-level `g/a/<baseVersion>/maven-metadata.xml` (`<version>`
 *    is the `-SNAPSHOT` version, `versioning/snapshot` carries the timestamp and buildNumber) and an
 *    artifact-level `g/a/maven-metadata.xml` (a `<versions>` list, no `<version>` of its own); a
 *    release deploy writes the artifact-level file only.
 */
import { zipSync } from 'fflate';

import { env } from '../env.js';
import { withBackoff429 } from '../scenarios/remote-throttle.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import {
  adminCredential,
  authHeader,
  msgIdOf,
  type RawResponse,
  sha256Hex,
  withBackoff429Response,
} from './raw-http.js';

export { adminCredential, authHeader, sha256Hex, type RawResponse };

/** A scenario's `groupId:artifactId` package name, split. */
export function splitPackageName(packageName: string): [string, string] {
  const parts = packageName.split(':');
  if (parts.length !== 2) {
    throw new Error(`maven adapter: expected "groupId:artifactId", got "${packageName}"`);
  }
  return [parts[0], parts[1]];
}

/** `io.repsy.e2e.x` -> `io/repsy/e2e/x`. */
export function groupPath(groupId: string): string {
  return groupId.replace(/\./g, '/');
}

/** The repo-relative directory of an artifact: `g/a`. */
export function artifactDir(groupId: string, artifactId: string): string {
  return `${groupPath(groupId)}/${artifactId}`;
}

/** The repo-relative directory of one version: `g/a/<version>`. */
export function versionDir(groupId: string, artifactId: string, version: string): string {
  return `${artifactDir(groupId, artifactId)}/${version}`;
}

/** `1.0-SNAPSHOT` -> `1.0`. */
export function baseVersion(version: string): string {
  return version.replace(/-SNAPSHOT$/, '');
}

export function isSnapshotVersion(version: string): boolean {
  return version.endsWith('-SNAPSHOT');
}

/** Maven's snapshot timestamp form, `yyyyMMdd.HHmmss` in UTC. */
export function snapshotTimestamp(at: Date = new Date()): string {
  const iso = at.toISOString(); // 2026-09-21T10:10:10.123Z
  return `${iso.slice(0, 10).replace(/-/g, '')}.${iso.slice(11, 19).replace(/:/g, '')}`;
}

function url(repoName: string, relPath: string): string {
  return `${env.repoBaseUrl}/${repoName}/${relPath}`;
}

/**
 * A raw PUT. `contentType` is mandatory on purpose: without one the body is consumed as form data
 * before the handler sees it and the server answers 400, hiding the status the caller wants to pin.
 */
export async function rawPut(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
  body: Uint8Array | string,
  contentType: string,
): Promise<RawResponse> {
  return withBackoff429Response(async () => {
    const res = await fetch(url(repoName, relPath), {
      method: 'PUT',
      headers: { ...authHeader(credential), 'Content-Type': contentType },
      body: typeof body === 'string' ? body : new Uint8Array(body),
    });
    const bytes = Buffer.from(await res.arrayBuffer());
    return { status: res.status, msgId: msgIdOf(bytes), body: bytes };
  });
}

export async function rawGet(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
): Promise<RawResponse> {
  return withBackoff429Response(async () => {
    const res = await fetch(url(repoName, relPath), { headers: authHeader(credential) });
    const bytes = Buffer.from(await res.arrayBuffer());
    return { status: res.status, msgId: msgIdOf(bytes), body: bytes };
  });
}

/** What a raw `HEAD` shows: the status and the headers a client such as Ivy or sbt reads (RPS-1368). */
export interface RawHeadResponse {
  status: number;
  /** `Content-Length`, or `null` when the server sent none. */
  contentLength: number | null;
  contentType: string | null;
  /** The bytes a HEAD carried, which must be none. */
  bodyLength: number;
}

/** A raw `HEAD`: answered like the `GET` of the same path, without the body (RPS-1368). */
export async function rawHead(
  repoName: string,
  credential: MaterializedCredential,
  relPath: string,
): Promise<RawHeadResponse> {
  let last: RawHeadResponse | undefined;
  await withBackoff429(async () => {
    const res = await fetch(url(repoName, relPath), {
      method: 'HEAD',
      headers: authHeader(credential),
    });
    const length = res.headers.get('content-length');
    last = {
      status: res.status,
      contentLength: length === null ? null : Number(length),
      contentType: res.headers.get('content-type'),
      bodyLength: (await res.arrayBuffer()).byteLength,
    };
    return res.status;
  });
  return last as unknown as RawHeadResponse;
}

/** The entries of a directory listing page (`../` excluded); sub-directories end in `/`. */
export function parseListing(html: string): string[] {
  const entries: string[] = [];
  for (const match of html.matchAll(/<a href="([^"]+)"/g)) {
    if (match[1] !== '../') {
      entries.push(decodeURIComponent(match[1]));
    }
  }
  return entries;
}

/** Repo-relative path -> sha256 of that file's content. */
export type RepoTree = Record<string, string>;

/**
 * Every file of a repo, found by walking its directory listings from the root, fingerprinted by
 * content. An empty (or never-written) repo is an empty tree. Two trees being equal means a
 * request in between stored nothing and changed nothing, which is what a refused deploy must leave.
 */
export async function repoTree(
  repoName: string,
  credential: MaterializedCredential = adminCredential(),
): Promise<RepoTree> {
  const tree: RepoTree = {};

  async function walk(dir: string): Promise<void> {
    const listing = await rawGet(repoName, credential, dir);
    if (listing.status === 404) {
      return;
    }
    if (listing.status !== 200) {
      throw new Error(`repoTree: GET ${dir || '/'} of "${repoName}" answered ${listing.status}`);
    }
    for (const entry of parseListing(listing.body.toString('utf8'))) {
      if (entry.endsWith('/')) {
        await walk(`${dir}${entry}`);
        continue;
      }
      const file = await rawGet(repoName, credential, `${dir}${entry}`);
      if (file.status !== 200) {
        throw new Error(`repoTree: GET ${dir}${entry} of "${repoName}" answered ${file.status}`);
      }
      tree[`${dir}${entry}`] = sha256Hex(file.body);
    }
  }

  await walk('');
  return Object.fromEntries(Object.entries(tree).sort(([a], [b]) => a.localeCompare(b)));
}

/**
 * A just-valid POM for a raw seed: the server reads it to register the artifact version. `extraXml` is
 * spliced in before `</project>` (for example a `<licenses>` or `<developers>` block), and is the
 * caller's own, already well-formed XML.
 */
export function minimalPom(
  groupId: string,
  artifactId: string,
  version: string,
  extraXml = '',
): string {
  return (
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<project xmlns="http://maven.apache.org/POM/4.0.0">\n' +
    '  <modelVersion>4.0.0</modelVersion>\n' +
    `  <groupId>${groupId}</groupId>\n` +
    `  <artifactId>${artifactId}</artifactId>\n` +
    `  <version>${version}</version>\n` +
    '  <packaging>jar</packaging>\n' +
    extraXml +
    '</project>\n'
  );
}

/**
 * A tiny but real jar (a zip with `META-INF/MANIFEST.MF` and a marker file), hand-built with
 * `fflate` so a raw seed never needs a JDK or `mvn` (the UI runner image has neither). The server
 * never opens a jar; the bytes only need to be stable and distinct per coordinate.
 */
export function buildJar(opts: { groupId: string; artifactId: string; version: string }): Buffer {
  const manifest =
    'Manifest-Version: 1.0\n' +
    `Implementation-Title: ${opts.artifactId}\n` +
    `Implementation-Version: ${opts.version}\n` +
    'Created-By: repsy-e2e\n';
  const marker = `e2e ${opts.groupId}:${opts.artifactId}@${opts.version}\n`;
  return Buffer.from(
    zipSync(
      {
        'META-INF/MANIFEST.MF': new TextEncoder().encode(manifest),
        'e2e-marker.txt': new TextEncoder().encode(marker),
      },
      { level: 0 },
    ),
  );
}

/** The version-level `g/a/<baseVersion>/maven-metadata.xml` a snapshot deploy writes. */
export function versionMetadataXml(opts: {
  groupId: string;
  artifactId: string;
  version: string;
  timestamp: string;
  buildNumber: number;
}): string {
  const value = `${baseVersion(opts.version)}-${opts.timestamp}-${opts.buildNumber}`;
  const updated = opts.timestamp.replace('.', '');
  const snapshotVersion = (extension: string): string =>
    '      <snapshotVersion>\n' +
    `        <extension>${extension}</extension>\n` +
    `        <value>${value}</value>\n` +
    `        <updated>${updated}</updated>\n` +
    '      </snapshotVersion>\n';
  return (
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<metadata modelVersion="1.1.0">\n' +
    `  <groupId>${opts.groupId}</groupId>\n` +
    `  <artifactId>${opts.artifactId}</artifactId>\n` +
    '  <versioning>\n' +
    `    <lastUpdated>${updated}</lastUpdated>\n` +
    '    <snapshot>\n' +
    `      <timestamp>${opts.timestamp}</timestamp>\n` +
    `      <buildNumber>${opts.buildNumber}</buildNumber>\n` +
    '    </snapshot>\n' +
    '    <snapshotVersions>\n' +
    snapshotVersion('pom') +
    snapshotVersion('jar') +
    '    </snapshotVersions>\n' +
    '  </versioning>\n' +
    `  <version>${opts.version}</version>\n` +
    '</metadata>\n'
  );
}

/** The artifact-level `g/a/maven-metadata.xml`: every version of both kinds, no `<version>`. */
export function artifactMetadataXml(opts: {
  groupId: string;
  artifactId: string;
  versions: readonly string[];
}): string {
  return (
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<metadata>\n' +
    `  <groupId>${opts.groupId}</groupId>\n` +
    `  <artifactId>${opts.artifactId}</artifactId>\n` +
    '  <versioning>\n' +
    '    <versions>\n' +
    opts.versions.map((version) => `      <version>${version}</version>\n`).join('') +
    '    </versions>\n' +
    '    <lastUpdated>20260101000000</lastUpdated>\n' +
    '  </versioning>\n' +
    '</metadata>\n'
  );
}

export interface VersionMetadata {
  /** The top-level `<version>`: the `-SNAPSHOT` version this file describes. */
  version?: string;
  timestamp?: string;
  buildNumber?: number;
  /** `<extension>` -> `<value>` of each `snapshotVersions/snapshotVersion`. */
  snapshotVersions: Record<string, string>;
}

function tag(xml: string, name: string): string | undefined {
  return new RegExp(`<${name}>([^<]*)</${name}>`).exec(xml)?.[1];
}

/** Reads the fields of a version-level snapshot `maven-metadata.xml` that these tests assert on. */
export function parseVersionMetadata(xml: string): VersionMetadata {
  const snapshotVersions: Record<string, string> = {};
  for (const block of xml.matchAll(/<snapshotVersion>([\s\S]*?)<\/snapshotVersion>/g)) {
    const extension = tag(block[1], 'extension');
    const value = tag(block[1], 'value');
    if (extension && value) {
      snapshotVersions[extension] = value;
    }
  }
  const buildNumber = tag(xml, 'buildNumber');
  return {
    // The versioning block holds no <version>, but the snapshotVersion blocks might in future.
    version: tag(xml.replace(/<versioning>[\s\S]*<\/versioning>/, ''), 'version'),
    timestamp: tag(xml, 'timestamp'),
    buildNumber: buildNumber === undefined ? undefined : Number(buildNumber),
    snapshotVersions,
  };
}

/** The `<versions>` list of an artifact-level `maven-metadata.xml`. */
export function parseArtifactVersions(xml: string): string[] {
  const block = /<versions>([\s\S]*?)<\/versions>/.exec(xml)?.[1] ?? '';
  return [...block.matchAll(/<version>([^<]*)<\/version>/g)].map((match) => match[1]);
}
