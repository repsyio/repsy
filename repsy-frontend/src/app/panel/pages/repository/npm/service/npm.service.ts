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
  NpmPackageListItem,
  NpmPackagesApi,
  NpmScopesApi,
  PackageDistributionTagMapListItem,
  PackageVersionDetail,
  PackageVersionListItem,
  RepoPermissionInfo,
  ReposApi,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';

@Injectable({
  providedIn: 'root',
})
export class NpmService {
  readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly reposApi: ReposApi,
    private readonly npmPackagesApi: NpmPackagesApi,
    private readonly npmScopesApi: NpmScopesApi,
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

  searchPackages(
    scope: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<NpmPackageListItem>> {
    return this.npmPackagesApi
      .listNpmPackages(this.repoName, scope || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<NpmPackageListItem>));
  }

  searchScopedPackages(
    scope: string,
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<NpmPackageListItem>> {
    return this.npmScopesApi
      .listNpmPackagesByScope(scope, this.repoName, name || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<NpmPackageListItem>));
  }

  searchUnscopedPackages(
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<NpmPackageListItem>> {
    return this.npmScopesApi
      .listUnscopedNpmPackages(this.repoName, name || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<NpmPackageListItem>));
  }

  searchPackageVersions(
    packageName: string,
    scopeName: string,
    version: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<PackageVersionListItem>> {
    const sort = [`${sortOption.column},${sortOption.type}`];
    const call = scopeName
      ? this.npmScopesApi.listNpmScopedPackageVersions(
          scopeName,
          packageName,
          this.repoName,
          version || undefined,
          pageIndex,
          pageSize,
          sort,
        )
      : this.npmPackagesApi.listNpmPackageVersions(
          packageName,
          this.repoName,
          version || undefined,
          pageIndex,
          pageSize,
          sort,
        );
    return call.pipe(
      map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<PackageVersionListItem>),
    );
  }

  fetchPackageTags(packageName: string, scopeName: string): Observable<PackageDistributionTagMapListItem[]> {
    const call = scopeName
      ? this.npmScopesApi.listNpmScopedPackageTags(scopeName, packageName, this.repoName)
      : this.npmPackagesApi.listNpmPackageTags(packageName, this.repoName);
    return call.pipe(map((r) => r ?? []));
  }

  fetchPackageVersion(
    packageName: string,
    scopeName: string,
    versionName: string,
  ): Observable<PackageVersionDetail> {
    const call = scopeName
      ? this.npmScopesApi.getNpmScopedPackageVersion(scopeName, packageName, versionName, this.repoName)
      : this.npmPackagesApi.getNpmPackageVersion(packageName, versionName, this.repoName);
    return call.pipe(map((r) => r as unknown as PackageVersionDetail));
  }

  deletePackage(packageName: string, scopeName: string): Observable<void> {
    const call = scopeName
      ? this.npmScopesApi.deleteScopedNpmPackage(scopeName, packageName, this.repoName)
      : this.npmPackagesApi.deleteNpmPackage(packageName, this.repoName);
    return call.pipe(map(() => undefined));
  }

  deletePackageVersion(packageName: string, scopeName: string, versionName: string): Observable<void> {
    const call = scopeName
      ? this.npmScopesApi.deleteNpmScopedPackageVersion(scopeName, packageName, versionName, this.repoName)
      : this.npmPackagesApi.deleteNpmPackageVersion(packageName, versionName, this.repoName);
    return call.pipe(map(() => undefined));
  }
}
