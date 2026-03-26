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

import { inject } from '@angular/core';
import { CanMatchFn, Router, Routes } from '@angular/router';
import { catchError, map, of, take } from 'rxjs';

import { RepoLookupService, RepoType } from './repo-lookup.service';

const canMatchRepoType = (expectedType: RepoType): CanMatchFn => {
  return () => {
    const router = inject(Router);
    const repoLookupService = inject(RepoLookupService);

    const navigation = router.getCurrentNavigation();
    const url = navigation?.extractedUrl;

    if (!url) {
      return false;
    }

    const rootChildren = url.root.children;
    const primarySegments = rootChildren['primary']?.segments;

    if (!primarySegments || primarySegments.length < 1) {
      return false;
    }

    const repoName = primarySegments[0].path;

    return repoLookupService.checkRepoType(repoName).pipe(
      take(1),
      map((fetchedType) => {
        return fetchedType === expectedType;
      }),
      catchError(() => {
        return of(false);
      }),
    );
  };
};

export const REPOSITORY_DYNAMIC_ROUTES: Routes = [
  {
    path: '',
    canMatch: [canMatchRepoType('maven')],
    loadChildren: () => import('../maven/maven.routes').then((m) => m.MAVEN_ROUTES),
  },
  {
    path: '',
    canMatch: [canMatchRepoType('npm')],
    loadChildren: () => import('../npm/npm.routes').then((m) => m.NPM_ROUTES),
  },
  {
    path: '',
    canMatch: [canMatchRepoType('pypi')],
    loadChildren: () => import('../pypi/pypi.routes').then((m) => m.PYPI_ROUTES),
  },
  {
    path: '',
    canMatch: [canMatchRepoType('docker')],
    loadChildren: () => import('../docker/docker.routes').then((m) => m.DOCKER_ROUTES),
  },
];
