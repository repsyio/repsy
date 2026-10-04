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
import { BehaviorSubject, firstValueFrom, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';

import {
  DeployTokenForm,
  DeployTokenInfoListItem,
  NuGetDeletedItem,
  NugetPackageControllerService,
  NuGetPackageInfo,
  NuGetPackageListItem,
  NuGetVersionInfo,
  NuGetVersionListItem,
  ProtocolDeployTokenControllerService,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
  RepoSettingsForm,
  RepoSettingsInfo,
  RepoUpdateForm,
  RepoUsageInfo,
  TokenInfo,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';

@Injectable({
  providedIn: 'root',
})
export class NugetService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly protocolDeployTokenControllerService: ProtocolDeployTokenControllerService,
    private readonly nugetPackageControllerService: NugetPackageControllerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public selectRepository(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.protocolRepoControllerService
      .getRepoPermissions(repoName)
      .pipe(tap((info) => this.repoSubject.next(info)));
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject.next(null);
  }

  public async fetchRepositoryUsage(): Promise<RepoUsageInfo> {
    return firstValueFrom(this.protocolRepoControllerService.getRepoUsage(this.repoName));
  }

  public async fetchRepositorySettings(): Promise<RepoSettingsInfo> {
    const response = await firstValueFrom(this.protocolRepoControllerService.getRepoSettings(this.repoName));
    return response;
  }

  public async updateRepoSettings(repoSettingsForm: RepoSettingsForm): Promise<void> {
    await firstValueFrom(this.protocolRepoControllerService.updateRepoSettings(this.repoName, repoSettingsForm));
  }

  public async updateRepositoryName(repositoryNameForm: RepoUpdateForm): Promise<void> {
    await firstValueFrom(this.protocolRepoControllerService.updateRepo(this.repoName, repositoryNameForm));

    const active = this.repoSubject.getValue();
    if (active) {
      this.repoSubject.next({ ...active, repoName: repositoryNameForm.name ?? active.repoName });
    }
  }

  public async updateRepoDescription(repositoryDescriptionForm: RepoUpdateForm): Promise<void> {
    await firstValueFrom(this.protocolRepoControllerService.updateRepo(this.repoName, repositoryDescriptionForm));
  }

  public async deleteRepository(repoName: string): Promise<void> {
    await firstValueFrom(this.protocolRepoControllerService.deleteRepo(repoName));
  }

  public async fetchRepositoryPackages(
    query: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<NuGetPackageListItem>> {
    const response = await firstValueFrom(
      this.nugetPackageControllerService.searchNugetPackages(this.repoName, query || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ]),
    );
    return this.toPagedData(response.data);
  }

  public async fetchPackage(packageId: string): Promise<NuGetPackageInfo> {
    const response = await firstValueFrom(this.nugetPackageControllerService.getNugetPackage(packageId, this.repoName));
    return response.data!;
  }

  public async fetchPackageVersions(
    packageId: string,
    query: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<NuGetVersionListItem>> {
    const response = await firstValueFrom(
      this.nugetPackageControllerService.listNugetVersions(
        packageId,
        this.repoName,
        query || undefined,
        pageIndex,
        pageSize,
        [`${sortOption.column},${sortOption.type}`],
      ),
    );
    return this.toPagedData(response.data);
  }

  public async fetchPackageVersion(packageId: string, version: string): Promise<NuGetVersionInfo> {
    const response = await firstValueFrom(
      this.nugetPackageControllerService.getNugetVersion(packageId, version, this.repoName),
    );
    return response.data!;
  }

  public async deletePackage(packageId: string): Promise<NuGetDeletedItem> {
    const response = await firstValueFrom(
      this.nugetPackageControllerService.deleteNugetPackage(packageId, this.repoName),
    );
    return response.data!;
  }

  public async deletePackageVersion(packageId: string, version: string): Promise<NuGetDeletedItem> {
    const response = await firstValueFrom(
      this.nugetPackageControllerService.deleteNugetVersion(packageId, version, this.repoName),
    );
    return response.data!;
  }

  public async getDeployTokens(pageNumber: number, pageSize: number): Promise<PagedData<DeployTokenInfoListItem>> {
    const response = await firstValueFrom(
      this.protocolDeployTokenControllerService.listDeployTokens(this.repoName, pageNumber, pageSize),
    );
    return this.toPagedData(response);
  }

  public async rotateDeployToken(tokenId: string): Promise<string> {
    return firstValueFrom(this.protocolDeployTokenControllerService.rotateDeployToken(tokenId, this.repoName));
  }

  public async createDeployToken(form: DeployTokenForm): Promise<TokenInfo> {
    return firstValueFrom(this.protocolDeployTokenControllerService.createDeployToken(this.repoName, form));
  }

  public async revokeDeployToken(tokenId: string): Promise<void> {
    await firstValueFrom(this.protocolDeployTokenControllerService.revokeDeployToken(tokenId, this.repoName));
  }

  private toPagedData<T>(data: { content?: T[]; page?: unknown } | undefined): PagedData<T> {
    return { content: data?.content ?? [], page: data?.page } as unknown as PagedData<T>;
  }
}
