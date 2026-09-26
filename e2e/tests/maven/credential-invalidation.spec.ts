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
 * RPS-1481 for Maven: the real `mvn deploy` with a credential that has just stopped being valid (a
 * changed password, a deleted user, a revoked or rotated deploy token). The scenarios live in
 * `scenarios/credential-invalidation.ts`, shared with `tests/npm/credential-invalidation.spec.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { mavenAdapter } from '../../src/clients/maven-adapter.js';
import {
  adminCredential,
  rawGet,
  splitPackageName,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { registerCredentialInvalidation } from '../../src/scenarios/credential-invalidation.js';

registerCredentialInvalidation({
  adapter: mavenAdapter,
  repoType: RepoType.MAVEN,
  isStored: async (repoName, packageName, version) => {
    const [groupId, artifactId] = splitPackageName(packageName);
    const pom = `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}.pom`;
    const res = await rawGet(repoName, adminCredential(), pom);
    if (res.status !== 200 && res.status !== 404) {
      throw new Error(`GET ${pom} as admin answered ${res.status}, expected 200 or 404`);
    }
    return res.status === 200;
  },
});
