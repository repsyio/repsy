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
import { buildArtifactDetailRoute } from './security-detail-route.util';

describe('buildArtifactDetailRoute', () => {
  it('returns null when the repo, artifact name or version is missing', () => {
    expect(buildArtifactDetailRoute(RepoType.Pypi, undefined, 'requests', '2.0.0')).toBeNull();
    expect(buildArtifactDetailRoute(RepoType.Pypi, '', 'requests', '2.0.0')).toBeNull();
    expect(buildArtifactDetailRoute(RepoType.Pypi, 'repo', undefined, '2.0.0')).toBeNull();
    expect(buildArtifactDetailRoute(RepoType.Pypi, 'repo', 'requests', undefined)).toBeNull();
    expect(buildArtifactDetailRoute(RepoType.Pypi, 'repo', 'requests', '')).toBeNull();
  });

  it('returns null for an unknown or missing repo type', () => {
    expect(buildArtifactDetailRoute(undefined, 'repo', 'requests', '2.0.0')).toBeNull();
  });

  describe('Maven', () => {
    it('splits groupId:artifactId into separate path segments', () => {
      expect(buildArtifactDetailRoute(RepoType.Maven, 'repo', 'org.acme:core', '1.0.0')).toEqual({
        path: '/repo/org.acme/core/1.0.0',
      });
    });

    it('keeps everything after the first colon in the artifactId', () => {
      expect(buildArtifactDetailRoute(RepoType.Maven, 'repo', 'org.acme:core:extra', '1.0.0')).toEqual({
        path: '/repo/org.acme/core:extra/1.0.0',
      });
    });

    it('returns null when the name has no groupId separator', () => {
      expect(buildArtifactDetailRoute(RepoType.Maven, 'repo', 'core', '1.0.0')).toBeNull();
    });
  });

  describe('npm', () => {
    it('routes a scoped package through its scope, without the @', () => {
      expect(buildArtifactDetailRoute(RepoType.Npm, 'repo', '@acme/widget', '1.2.3')).toEqual({
        path: '/repo/acme/widget/1.2.3',
      });
    });

    it('routes an unscoped package through the ~ placeholder scope', () => {
      expect(buildArtifactDetailRoute(RepoType.Npm, 'repo', 'left-pad', '1.2.3')).toEqual({
        path: '/repo/~/left-pad/1.2.3',
      });
    });

    it('returns null for a scoped name without a package part', () => {
      expect(buildArtifactDetailRoute(RepoType.Npm, 'repo', '@acme', '1.2.3')).toBeNull();
    });
  });

  describe('name and version repo types', () => {
    const types = [RepoType.Cargo, RepoType.Helm, RepoType.Nuget, RepoType.Pypi, RepoType.Ruby];

    types.forEach((type) => {
      it(`routes ${type} as /repo/name/version`, () => {
        expect(buildArtifactDetailRoute(type, 'repo', 'pkg', '1.0.0')).toEqual({ path: '/repo/pkg/1.0.0' });
      });
    });
  });

  describe('Go', () => {
    it('passes the module path and version as query params', () => {
      expect(buildArtifactDetailRoute(RepoType.Golang, 'repo', 'github.com/acme/mod', 'v1.0.0')).toEqual({
        path: '/repo/modules/version',
        queryParams: { modulePath: 'github.com/acme/mod', version: 'v1.0.0' },
      });
    });
  });

  describe('Docker', () => {
    it('routes a tag to the detail page', () => {
      expect(buildArtifactDetailRoute(RepoType.Docker, 'repo', 'acme/app', 'latest')).toEqual({
        path: '/repo/acme/app/latest/detail',
      });
    });

    it('returns null for a digest, which has no detail page', () => {
      expect(buildArtifactDetailRoute(RepoType.Docker, 'repo', 'acme/app', 'sha256:abc123')).toBeNull();
    });
  });
});
