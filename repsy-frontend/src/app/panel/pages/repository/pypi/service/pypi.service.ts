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
  PypiPackageListItem,
  PypiPackagesApi,
  ReleaseDetail,
  ReleaseListItem,
  RepoPermissionInfo,
  ReposApi,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';

@Injectable({
  providedIn: 'root',
})
export class PypiService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;
  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly reposApi: ReposApi,
    private readonly pypiPackagesApi: PypiPackagesApi,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public fetchRepoPermission(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.reposApi.getRepoPermissions(repoName).pipe(tap((info) => this.repoSubject.next(info)));
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject.next(null);
  }

  public fetchRepositoryPackagesLikeName(
    name: string,
    sort: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<PypiPackageListItem>> {
    return this.pypiPackagesApi
      .listPypiPackages(this.repoName, name || undefined, pageIndex, pageSize, [`${sort.column},${sort.type}`])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<PypiPackageListItem>));
  }

  public fetchPackageReleasesLikeName(
    packageName: string,
    version: string,
    sort: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ReleaseListItem>> {
    return this.pypiPackagesApi
      .listPypiVersions(packageName, this.repoName, version || undefined, pageIndex, pageSize, [
        `${sort.column},${sort.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<ReleaseListItem>));
  }

  public deletePackage(packageName: string): Observable<void> {
    return this.pypiPackagesApi.deletePypiPackage(packageName, this.repoName).pipe(map(() => undefined));
  }

  public fetchRelease(packageName: string, release: string): Observable<ReleaseDetail> {
    return this.pypiPackagesApi.getPypiVersion(packageName, release, this.repoName);
  }

  public deleteRelease(packageName: string, releaseVersion: string): Observable<void> {
    return this.pypiPackagesApi
      .deletePypiVersion(packageName, releaseVersion, this.repoName)
      .pipe(map(() => undefined));
  }
}
