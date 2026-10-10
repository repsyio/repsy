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
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { map, tap } from 'rxjs/operators';

import {
  GemListItem,
  GemVersionInfo,
  GemVersionListItem,
  RepoPermissionInfo,
  ReposApi,
  RubyGemsApi,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';

@Injectable({
  providedIn: 'root',
})
export class RubyService {
  readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly reposApi: ReposApi,
    private readonly rubyGemsApi: RubyGemsApi,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  fetchRepoPermission(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.reposApi.getRepoPermissions(repoName).pipe(tap((info) => this.repoSubject.next(info)));
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject.next(null);
  }

  searchGems(
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<GemListItem>> {
    return this.rubyGemsApi
      .listGems(this.repoName, search || undefined, pageIndex, pageSize, [`${sortOption.column},${sortOption.type}`])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<GemListItem>));
  }

  fetchGemVersions(
    gemName: string,
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<GemVersionListItem>> {
    return this.rubyGemsApi
      .listGemVersions(gemName, this.repoName, search || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<GemVersionListItem>));
  }

  fetchGemVersion(gemName: string, version: string, platform?: string): Observable<GemVersionInfo> {
    return this.rubyGemsApi.getGemVersion(gemName, version, this.repoName, platform);
  }

  deleteGem(gemName: string): Observable<void> {
    return this.rubyGemsApi.deleteGem(gemName, this.repoName).pipe(map(() => undefined));
  }

  deleteGemVersion(gemName: string, version: string, platform: string): Observable<void> {
    return this.rubyGemsApi.deleteGemVersion(gemName, version, this.repoName, platform).pipe(map(() => undefined));
  }
}
