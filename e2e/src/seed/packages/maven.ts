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

/**
 * Maven seeder: PUTs a real (fflate-built) jar and a minimal POM, then the artifact-level
 * `maven-metadata.xml` a real deploy ends with. `opts.name` is `groupId:artifactId`.
 */
import { adminCredential } from '../../clients/raw-http.js';
import {
  artifactDir,
  artifactMetadataXml,
  buildJar,
  minimalPom,
  rawPut,
  splitPackageName,
  versionDir,
} from '../../clients/maven-raw.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

const OCTET = 'application/octet-stream';
const XML = 'application/xml';

export const seedMaven: PackageSeeder = async (repoName, ctx, opts) => {
  const name = opts.name ?? defaultPackageName('maven', ctx.runId, opts.index);
  const version = opts.version ?? DEFAULT_VERSION;
  const [groupId, artifactId] = splitPackageName(name);
  const admin = adminCredential();
  const dir = versionDir(groupId, artifactId, version);
  const base = `${dir}/${artifactId}-${version}`;

  // A real client sends the artifacts first, then the metadata that lists them.
  expectPublished(
    await rawPut(repoName, admin, `${base}.jar`, buildJar({ groupId, artifactId, version }), OCTET),
    `PUT ${base}.jar`,
  );
  expectPublished(
    await rawPut(repoName, admin, `${base}.pom`, minimalPom(groupId, artifactId, version), OCTET),
    `PUT ${base}.pom`,
  );
  expectPublished(
    await rawPut(
      repoName,
      admin,
      `${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
      artifactMetadataXml({ groupId, artifactId, versions: [version] }),
      XML,
    ),
    `PUT ${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
  );

  return { protocol: 'maven', repoName, name, version, extra: {} };
};
