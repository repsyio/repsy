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

import { HttpContext } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { BehaviorSubject, finalize, map, Observable, of, share, tap } from 'rxjs';

import { ReposApi } from '../../../../../generated/api';
import { SILENT_ERROR } from '../../../../shared/interceptor/error-handler.interceptor';
import { RepoRouteSlug, toRouteSlug } from '../../../shared/util/repo-api-type';

export interface RepoContext {
  repoName: string;
  repoType: RepoRouteSlug;
}

@Injectable({
  providedIn: 'root',
})
export class RepoLookupService {
  private readonly cache = new Map<string, RepoRouteSlug>();

  /**
   * One request per repository name in flight at a time (RPS-1670): an unknown route's resolver and its
   * nine `canMatch` guards (one per protocol, `repository-dynamic.routes.ts`) all ask for the same name
   * before any of them can populate `cache`, so without this every one of them fired its own HTTP call -
   * about ten - and the error interceptor toasted "Repository not found" once per call. `share()`
   * multicasts the single underlying request (and its error) to every concurrent caller instead.
   */
  private readonly inFlight = new Map<string, Observable<RepoRouteSlug>>();

  private readonly currentRepoSubject = new BehaviorSubject<RepoContext | null>(null);
  readonly currentRepo$ = this.currentRepoSubject.asObservable();

  constructor(private readonly reposApi: ReposApi) {}

  get currentRepo(): RepoContext | null {
    return this.currentRepoSubject.getValue();
  }

  getRepoType(repoName: string): Observable<RepoRouteSlug> {
    const cachedType = this.cache.get(repoName);

    if (cachedType) {
      this.currentRepoSubject.next({ repoName, repoType: cachedType });
      return of(cachedType);
    }

    return this.sharedFetch(repoName).pipe(tap((repoType) => this.currentRepoSubject.next({ repoName, repoType })));
  }

  checkRepoType(repoName: string): Observable<RepoRouteSlug> {
    const cachedType = this.cache.get(repoName);

    if (cachedType) {
      return of(cachedType);
    }

    return this.sharedFetch(repoName);
  }

  /**
   * The in-flight request for `repoName`, started fresh if none is running. Every caller in the same
   * tick (the resolver, and each protocol's `canMatch`) shares the one HTTP request through `share()`;
   * `cache` is filled once it succeeds, so the next lookup of the same name never re-fetches.
   */
  private sharedFetch(repoName: string): Observable<RepoRouteSlug> {
    const running = this.inFlight.get(repoName);
    if (running) {
      return running;
    }

    const request = this.fetchRepoType(repoName).pipe(
      tap((repoType) => this.cache.set(repoName, repoType)),
      finalize(() => this.inFlight.delete(repoName)),
      share(),
    );
    this.inFlight.set(repoName, request);
    return request;
  }

  private fetchRepoType(repoName: string): Observable<RepoRouteSlug> {
    // Structural: a route guard or resolver checking whether repoName exists, not a user action. Its
    // caller decides the outcome (canMatch says no, the resolver redirects to /not-found), so a 404 here
    // must not also raise the "Repository not found" toast (RPS-1670).
    return this.reposApi.getRepo(repoName, 'body', false, { context: new HttpContext().set(SILENT_ERROR, true) }).pipe(
      map((r) => {
        const slug = toRouteSlug(r.type);
        if (!slug) {
          throw new Error(`Unknown repository type "${r.type}" for ${repoName}`);
        }
        return slug;
      }),
    );
  }
}
