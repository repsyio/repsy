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

import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { BehaviorSubject, map, Observable, of, tap } from 'rxjs';

import { environment } from '../../../../../environments/environment';

export type RepoType = 'maven' | 'npm' | 'pypi' | 'docker';

export interface RepoContext {
  repoName: string;
  repoType: RepoType;
}

interface RepoTypeResponse {
  data: RepoType;
}

@Injectable({
  providedIn: 'root',
})
export class RepoLookupService {
  private readonly baseUrl = `${environment.apiBaseUrl}/api/repos`;
  private readonly cache = new Map<string, RepoType>();

  private readonly currentRepoSubject = new BehaviorSubject<RepoContext | null>(null);
  public readonly currentRepo$ = this.currentRepoSubject.asObservable();

  constructor(private readonly http: HttpClient) {}

  public get currentRepo(): RepoContext | null {
    return this.currentRepoSubject.getValue();
  }

  public getRepoType(repoName: string): Observable<RepoType> {
    const cacheKey = this.buildCacheKey(repoName);
    const cachedType = this.cache.get(cacheKey);

    if (cachedType) {
      this.currentRepoSubject.next({ repoName, repoType: cachedType });
      return of(cachedType);
    }

    return this.fetchRepoType(repoName).pipe(
      tap((repoType) => {
        this.cache.set(cacheKey, repoType);
        this.currentRepoSubject.next({ repoName, repoType });
      }),
    );
  }

  public checkRepoType(repoName: string): Observable<RepoType> {
    const cacheKey = this.buildCacheKey(repoName);
    const cachedType = this.cache.get(cacheKey);

    if (cachedType) {
      return of(cachedType);
    }

    return this.fetchRepoType(repoName).pipe(tap((repoType) => this.cache.set(cacheKey, repoType)));
  }

  private fetchRepoType(repoName: string): Observable<RepoType> {
    return this.http.get<RepoTypeResponse>(`${this.baseUrl}/${repoName}/format`).pipe(map((response) => response.data));
  }

  private buildCacheKey(repoName: string): string {
    return `${repoName}`;
  }
}
