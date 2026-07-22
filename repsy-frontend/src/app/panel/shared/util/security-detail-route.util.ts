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

import { RepoType } from '../../../../generated/api';

export interface ArtifactDetailRoute {
  path: string;
  queryParams?: Record<string, string>;
}

/**
 * Builds the version-detail route for an artifact, given its repo type — the same per-protocol
 * coordinate parsing used to link a repo/artifact/version triple (severity-summary rows, recent-scan
 * rows) to the page that actually shows it. Returns {@code null} when the route can't be resolved,
 * e.g. a Docker digest-referenced push ({@code artifactVersion} starting with {@code "sha256:"}) has
 * no corresponding tag-list entry, so it's left non-clickable rather than linking to a 404.
 */
export function buildArtifactDetailRoute(
  repoType: RepoType | undefined,
  repoName: string | undefined,
  artifactName: string | undefined,
  artifactVersion: string | undefined,
): ArtifactDetailRoute | null {
  if (!repoName || !artifactName || !artifactVersion) {
    return null;
  }

  switch (repoType) {
    case RepoType.Maven: {
      const separatorIndex = artifactName.indexOf(':');
      if (separatorIndex === -1) {
        return null;
      }
      const groupId = artifactName.slice(0, separatorIndex);
      const artifactId = artifactName.slice(separatorIndex + 1);
      return { path: `/${repoName}/${groupId}/${artifactId}/${artifactVersion}` };
    }
    case RepoType.Npm: {
      if (artifactName.startsWith('@')) {
        const slashIndex = artifactName.indexOf('/');
        if (slashIndex === -1) {
          return null;
        }
        const scopeName = artifactName.slice(1, slashIndex);
        const packageName = artifactName.slice(slashIndex + 1);
        return { path: `/${repoName}/${scopeName}/${packageName}/${artifactVersion}` };
      }
      return { path: `/${repoName}/~/${artifactName}/${artifactVersion}` };
    }
    case RepoType.Cargo:
    case RepoType.Helm:
    case RepoType.Nuget:
    case RepoType.Pypi:
    case RepoType.Ruby:
      return { path: `/${repoName}/${artifactName}/${artifactVersion}` };
    case RepoType.Golang:
      return {
        path: `/${repoName}/modules/version`,
        queryParams: { modulePath: artifactName, version: artifactVersion },
      };
    case RepoType.Docker:
      if (artifactVersion.startsWith('sha256:')) {
        return null;
      }
      return { path: `/${repoName}/${artifactName}/${artifactVersion}/detail` };
    default:
      return null;
  }
}
