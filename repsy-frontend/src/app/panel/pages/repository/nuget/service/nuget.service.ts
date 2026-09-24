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
import { map, tap } from 'rxjs/operators';

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
  RepoCreateForm,
  RepoDescriptionForm,
  RepoListInfo,
  RepoPermissionInfo,
  RepoRenameForm,
  RepoSettingsForm,
  RepoSettingsInfo,
  RepoType,
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

  public async createRepository(repoForm: RepoCreateForm): Promise<void> {
    await firstValueFrom(this.protocolRepoControllerService.createRepo(RepoType.Nuget, repoForm));
  }

  public async fetchRepositories(): Promise<RepoListInfo[]> {
    const response = await firstValueFrom(this.protocolRepoControllerService.getInfo(RepoType.Nuget));
    return response.data ?? [];
  }

  public async fetchRepositoryUsage(): Promise<RepoUsageInfo> {
    const response = await firstValueFrom(this.protocolRepoControllerService.getUsage(this.repoName));
    return response.data!;
  }

  public async fetchRepositorySettings(): Promise<RepoSettingsInfo> {
    const response = await firstValueFrom(this.protocolRepoControllerService.getSettings(this.repoName));
    return response.data!;
  }

  public async updateRepoSettings(repoSettingsForm: RepoSettingsForm): Promise<void> {
    await firstValueFrom(this.protocolRepoControllerService.updateSettings(this.repoName, repoSettingsForm));
  }

  public async updateRepositoryName(repositoryNameForm: RepoRenameForm): Promise<void> {
    await firstValueFrom(this.protocolRepoControllerService.rename(this.repoName, repositoryNameForm));

    const active = this.repoSubject.getValue();
    if (active) {
      this.repoSubject.next({ ...active, repoName: repositoryNameForm.name });
    }
  }

  public async updateRepoDescription(repositoryDescriptionForm: RepoDescriptionForm): Promise<void> {
    await firstValueFrom(
      this.protocolRepoControllerService.updateDescription(this.repoName, repositoryDescriptionForm),
    );
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
      this.nugetPackageControllerService.searchNugetPackages(
        { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
        this.repoName,
        query || undefined,
      ),
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
        { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
        this.repoName,
        query || undefined,
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
      this.protocolDeployTokenControllerService.listDeployTokens({ page: pageNumber, size: pageSize }, this.repoName),
    );
    return this.toPagedData(response.data);
  }

  public async rotateDeployToken(tokenId: string): Promise<string> {
    const response = await firstValueFrom(this.protocolDeployTokenControllerService.rotate(tokenId, this.repoName));
    return response.data!;
  }

  public async createDeployToken(form: DeployTokenForm): Promise<TokenInfo> {
    const response = await firstValueFrom(
      this.protocolDeployTokenControllerService.createDeployToken(this.repoName, form),
    );
    return response.data!;
  }

  public async revokeDeployToken(tokenId: string): Promise<void> {
    await firstValueFrom(this.protocolDeployTokenControllerService.revoke(tokenId, this.repoName));
  }

  private toPagedData<T>(data: { content?: T[]; page?: unknown } | undefined): PagedData<T> {
    return { content: data?.content ?? [], page: data?.page } as unknown as PagedData<T>;
  }
}
