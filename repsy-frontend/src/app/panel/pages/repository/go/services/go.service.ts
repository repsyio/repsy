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
  GoModuleInfo,
  GoModuleListItem,
  GoModulesApi,
  GoModuleVersionListItem,
  RepoPermissionInfo,
  ReposApi,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dtos/paged-data';
import { Sort } from '../../../../shared/dtos/sort';

@Injectable({
  providedIn: 'root',
})
export class GoService {
  readonly repoChanges$: Observable<RepoPermissionInfo>;

  private readonly repoSubject$ = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly reposApi: ReposApi,
    private readonly goModulesApi: GoModulesApi,
  ) {
    this.repoChanges$ = this.repoSubject$.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject$.getValue()?.repoName ?? '';
  }

  fetchRepoPermission(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.reposApi.getRepoPermissions(repoName).pipe(tap((info) => this.repoSubject$.next(info)));
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject$.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject$.next(null);
  }

  fetchModules(
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<GoModuleListItem>> {
    return this.goModulesApi
      .listGolangModules(this.repoName, search || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<GoModuleListItem>));
  }

  deleteModule(modulePath: string): Observable<void> {
    return this.goModulesApi.deleteGolangModule(modulePath, this.repoName).pipe(map(() => undefined));
  }

  fetchModuleVersions(
    modulePath: string,
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<GoModuleVersionListItem>> {
    return this.goModulesApi
      .listGolangModuleVersions(modulePath, this.repoName, search || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<GoModuleVersionListItem>),
      );
  }

  fetchModuleInfo(modulePath: string): Observable<GoModuleInfo> {
    return this.goModulesApi.getGolangModuleInfo(modulePath, this.repoName).pipe(map((r) => r));
  }

  deleteModuleVersion(modulePath: string, version: string): Observable<void> {
    return this.goModulesApi.deleteGolangModuleVersion(modulePath, version, this.repoName).pipe(map(() => undefined));
  }
}
