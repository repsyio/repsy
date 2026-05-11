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

import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';
import {
  NpmPackageApiControllerService,
  PackageDistributionTagMapListItem,
  NpmPackageListItem,
  PackageVersionListItem,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
  PackageVersionDetail
} from '../../../../../../generated/api';

@Injectable({
  providedIn: 'root',
})
export class NpmService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly npmPackageApiControllerService: NpmPackageApiControllerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public getRepository(repoName: string): Observable<RepoPermissionInfo> {
    return this.protocolRepoControllerService.getPermission(repoName).pipe(
      map(r => r.data!),
      tap(info => this.repoSubject.next(info)),
    );
  }

  public searchPackages(scope: string, sortOption: Sort, pageIndex: number, pageSize: number): Observable<PagedData<NpmPackageListItem>> {
    return this.npmPackageApiControllerService.listNpmPackages(
      { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
      this.repoName,
      scope || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<NpmPackageListItem>)),
    );
  }

  public searchScopedPackages(
    scope: string,
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<NpmPackageListItem>> {
    return this.npmPackageApiControllerService.listNpmPackagesByScope(
      scope,
      { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
      this.repoName,
      name || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<NpmPackageListItem>)),
    );
  }

  public searchUnscopedPackages(
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<NpmPackageListItem>> {
    return this.npmPackageApiControllerService.listFilterByScope(
      { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
      this.repoName,
      name || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<NpmPackageListItem>)),
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
    const pageable = { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] };
    const call = scopeName
      ? this.npmPackageApiControllerService.listNpmScopedPackageVersions(scopeName, packageName, pageable, this.repoName, version || undefined)
      : this.npmPackageApiControllerService.listVersions(packageName, pageable, this.repoName, version || undefined);
    return call.pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<PackageVersionListItem>)),
    );
  }

  public fetchPackageTags(packageName: string, scopeName: string): Observable<PackageDistributionTagMapListItem[]> {
    const call = scopeName
      ? this.npmPackageApiControllerService.listNpmScopedPackageTags(scopeName, packageName, this.repoName)
      : this.npmPackageApiControllerService.listTags(packageName, this.repoName);
    return call.pipe(map(r => r.data ?? []));
  }

  public fetchPackageVersion(packageName: string, scopeName: string, versionName: string): Observable<PackageVersionDetail> {
    const call = scopeName
      ? this.npmPackageApiControllerService.getNpmScopedPackageVersion(scopeName, packageName, versionName, this.repoName)
      : this.npmPackageApiControllerService.getVersion(packageName, versionName, this.repoName);
    return call.pipe(map(r => r.data as unknown as PackageVersionDetail));
  }

  public deletePackage(packageName: string, scopeName: string): Observable<void> {
    const call = scopeName
      ? this.npmPackageApiControllerService.deleteScopedNpmPackage(scopeName, packageName, this.repoName)
      : this.npmPackageApiControllerService.deleteNpmPackage(packageName, this.repoName);
    return call.pipe(map(() => undefined));
  }

  public deletePackageVersion(packageName: string, scopeName: string, versionName: string): Observable<void> {
    const call = scopeName
      ? this.npmPackageApiControllerService.deleteNpmScopedPackageVersion(scopeName, packageName, versionName, this.repoName)
      : this.npmPackageApiControllerService.deleteVersion(packageName, versionName, this.repoName);
    return call.pipe(map(() => undefined));
  }
}
