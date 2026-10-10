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
import { RedirectCommand, ResolveFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { RepoContext, RepoLookupService } from './repo-lookup.service';

export type { RepoContext };
export type RepoRouteData = RepoContext;

/**
 * Answers with a `RedirectCommand` to /not-found instead of calling `router.navigate()` from in here (RPS-1650). A
 * navigation started from a resolver is a NEW one: the entry of the unknown route stayed in the history and the
 * 404 page was pushed on top of it, and after a Back (which is a popstate the router then restores) the visitor
 * kept landing on the 404 page, whatever they pressed. A redirect is part of the navigation being resolved, so the
 * router replaces the entry of the route that was not found (like the guards' `UrlTree` redirects do).
 */
export const repoTypeResolver: ResolveFn<RepoRouteData | null> = (route) => {
  const repoLookupService = inject(RepoLookupService);
  const router = inject(Router);

  const repoName = route.paramMap.get('repoName');

  if (!repoName) {
    return of(new RedirectCommand(router.parseUrl('/not-found'), { state: { message: 'Invalid repository path' } }));
  }

  return repoLookupService.getRepoType(repoName).pipe(
    map((repoType): RepoRouteData => ({
      repoName,
      repoType,
    })),
    catchError(() => of(new RedirectCommand(router.parseUrl('/not-found')))),
  );
};
