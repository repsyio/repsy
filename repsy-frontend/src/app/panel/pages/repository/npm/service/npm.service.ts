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
  NpmPackageApiControllerService,
  NpmPackageListItem,
  NpmScopeApiControllerService,
  PackageDistributionTagMapListItem,
  PackageVersionDetail,
  PackageVersionListItem,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';

@Injectable({
  providedIn: 'root',
})
export class NpmService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly npmPackageApiControllerService: NpmPackageApiControllerService,
    private readonly npmScopeApiControllerService: NpmScopeApiControllerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public getRepository(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.protocolRepoControllerService.getPermission(repoName).pipe(
      map((r) => r.data!),
      tap((info) => this.repoSubject.next(info)),
    );
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject.next(null);
  }

  public searchPackages(
    scope: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<NpmPackageListItem>> {
    return this.npmPackageApiControllerService
      .listNpmPackages(this.repoName, scope || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map(
          (r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<NpmPackageListItem>,
        ),
      );
  }

  public searchScopedPackages(
    scope: string,
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<NpmPackageListItem>> {
    return this.npmScopeApiControllerService
      .listNpmPackagesByScope(scope, this.repoName, name || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map(
          (r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<NpmPackageListItem>,
        ),
      );
  }

  public searchUnscopedPackages(
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<NpmPackageListItem>> {
    return this.npmScopeApiControllerService
      .listUnscopedNpmPackages(this.repoName, name || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map(
          (r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<NpmPackageListItem>,
        ),
      );
  }

  public searchPackageVersions(
    packageName: string,
    scopeName: string,
    version: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<PackageVersionListItem>> {
    const sort = [`${sortOption.column},${sortOption.type}`];
    const call = scopeName
      ? this.npmPackageApiControllerService.listNpmScopedPackageVersions(
          scopeName,
          packageName,
          this.repoName,
          version || undefined,
          pageIndex,
          pageSize,
          sort,
        )
      : this.npmPackageApiControllerService.listNpmPackageVersions(
          packageName,
          this.repoName,
          version || undefined,
          pageIndex,
          pageSize,
          sort,
        );
    return call.pipe(
      map(
        (r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<PackageVersionListItem>,
      ),
    );
  }

  public fetchPackageTags(packageName: string, scopeName: string): Observable<PackageDistributionTagMapListItem[]> {
    const call = scopeName
      ? this.npmPackageApiControllerService.listNpmScopedPackageTags(scopeName, packageName, this.repoName)
      : this.npmPackageApiControllerService.listNpmPackageTags(packageName, this.repoName);
    return call.pipe(map((r) => r.data ?? []));
  }

  public fetchPackageVersion(
    packageName: string,
    scopeName: string,
    versionName: string,
  ): Observable<PackageVersionDetail> {
    const call = scopeName
      ? this.npmPackageApiControllerService.getNpmScopedPackageVersion(
          scopeName,
          packageName,
          versionName,
          this.repoName,
        )
      : this.npmPackageApiControllerService.getNpmPackageVersion(packageName, versionName, this.repoName);
    return call.pipe(map((r) => r.data as unknown as PackageVersionDetail));
  }

  public deletePackage(packageName: string, scopeName: string): Observable<void> {
    const call = scopeName
      ? this.npmPackageApiControllerService.deleteScopedNpmPackage(scopeName, packageName, this.repoName)
      : this.npmPackageApiControllerService.deleteNpmPackage(packageName, this.repoName);
    return call.pipe(map(() => undefined));
  }

  public deletePackageVersion(packageName: string, scopeName: string, versionName: string): Observable<void> {
    const call = scopeName
      ? this.npmPackageApiControllerService.deleteNpmScopedPackageVersion(
          scopeName,
          packageName,
          versionName,
          this.repoName,
        )
      : this.npmPackageApiControllerService.deleteNpmPackageVersion(packageName, versionName, this.repoName);
    return call.pipe(map(() => undefined));
  }
}
