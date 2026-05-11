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
  DeployTokenForm,
  PackageListItem,
  ProtocolDeployTokenControllerService,
  ProtocolRepoControllerService,
  PypiPackageControllerService,
  ReleaseDetail,
  ReleaseListItem,
  RepoCreateForm,
  RepoDescriptionForm,
  RepoInfo,
  RepoPermissionInfo,
  RepoRenameForm,
  RepoSettingsForm,
  RepoSettingsInfo,
  RepoType as ApiRepoType,
  RepoUsageInfo,
} from '../../../../../../generated/api';

import { PagedData } from '../../../../shared/dto/paged-data';
import { RepoListItem } from '../../../../shared/dto/repo/repo-list-item';
import { Sort } from '../../../../shared/dto/sort';
import { DeployTokenInfo } from '../../repo-settings/deploy-token/dto/deploy-token-info';
import { TokenCreateInfo } from '../../repo-settings/deploy-token/dto/token-create-info';

@Injectable({
  providedIn: 'root',
})
export class PypiService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;
  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly pypiPackageControllerService: PypiPackageControllerService,
    private readonly protocolDeployTokenControllerService: ProtocolDeployTokenControllerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public selectRepository(repoName: string): Observable<RepoPermissionInfo> {
    return this.protocolRepoControllerService.getPermission({} as RepoPermissionInfo, repoName).pipe(
      map(r => r.data!),
      tap(info => this.repoSubject.next(info)),
    );
  }

  public fetchRepositoryPackagesLikeName(name: string, sort: Sort, pageIndex: number, pageSize: number): Observable<PagedData<PackageListItem>> {
    return this.pypiPackageControllerService.listPypiPackages(
      {} as RepoInfo,
      { page: pageIndex, size: pageSize, sort: [`${sort.column},${sort.type}`] },
      this.repoName,
      name || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<PackageListItem>)),
    );
  }

  public fetchPackageReleasesLikeName(packageName: string, version: string, sort: Sort, pageIndex: number, pageSize: number): Observable<PagedData<ReleaseListItem>> {
    return this.pypiPackageControllerService.listReleases(
      {} as RepoInfo,
      packageName,
      { page: pageIndex, size: pageSize, sort: [`${sort.column},${sort.type}`] },
      this.repoName,
      version || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<ReleaseListItem>)),
    );
  }

  public deletePackage(packageName: string): Observable<void> {
    return this.pypiPackageControllerService.deletePypiPackage({} as RepoInfo, packageName, this.repoName).pipe(
      map(() => undefined),
    );
  }

  public fetchRelease(packageName: string, release: string): Observable<ReleaseDetail> {
    return this.pypiPackageControllerService.getRelease({} as RepoInfo, packageName, release, this.repoName).pipe(
      map(r => r.data!),
    );
  }

  public deleteRelease(packageName: string, releaseVersion: string): Observable<void> {
    return this.pypiPackageControllerService.deleteRelease({} as RepoInfo, packageName, releaseVersion, this.repoName).pipe(
      map(() => undefined),
    );
  }
}
