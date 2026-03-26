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
import { ResolveFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { RepoContext, RepoLookupService, RepoType } from './repo-lookup.service';

export type { RepoContext, RepoType };
export type RepoRouteData = RepoContext;

export const repoTypeResolver: ResolveFn<RepoRouteData | null> = (route) => {
  const repoLookupService = inject(RepoLookupService);
  const router = inject(Router);

  const repoName = route.paramMap.get('repoName');

  if (!repoName) {
    router.navigate(['/not-found'], {
      state: { message: 'Invalid repository path' },
    });
    return of(null);
  }

  return repoLookupService.getRepoType(repoName).pipe(
    map(
      (repoType): RepoRouteData => ({
        repoName,
        repoType,
      }),
    ),
    catchError(() => {
      router.navigate(['/not-found']);
      return of(null);
    }),
  );
};
