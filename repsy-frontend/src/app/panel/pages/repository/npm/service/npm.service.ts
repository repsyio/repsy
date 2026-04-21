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

import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, Subscriber } from 'rxjs';

import { environment } from '../../../../../../environments/environment';
import { ErrorHandlerService } from '../../../../../shared/error-handler/error-handler.service';
import { PagedData } from '../../../../shared/dto/paged-data';
import { RepoDescriptionForm } from '../../../../shared/dto/repo/repo-description-form';
import { RepoForm } from '../../../../shared/dto/repo/repo-form';
import { RepoListItem } from '../../../../shared/dto/repo/repo-list-item';
import { RepoNameForm } from '../../../../shared/dto/repo/repo-name-form';
import { RepoPermissionInfo } from '../../../../shared/dto/repo/repo-permission-info';
import { RepoSettingsForm } from '../../../../shared/dto/repo/repo-settings-form';
import { RepoUsageInfo } from '../../../../shared/dto/repo-usage-info';
import { RestResponse } from '../../../../shared/dto/rest-response';
import { Sort } from '../../../../shared/dto/sort';
import { RepositorySettingsInfo } from '../../pypi/dto/repository-settings-info';
import { DeployTokenInfo } from '../../repo-settings/deploy-token/dto/deploy-token-info';
import { TokenCreateInfo } from '../../repo-settings/deploy-token/dto/token-create-info';
import { DeployTokenForm } from '../../repo-settings/deploy-token/form/deploy-token-form';
import { PackageDistributionTagMapListItem } from '../dto/package-distribution-tag-map-list-item';
import { PackageListItem } from '../dto/package-list-item';
import { PackageVersionInfo } from '../dto/package-version-info';
import { PackageVersionListItem } from '../dto/package-version-list-item';

@Injectable({
  providedIn: 'root',
})
export class NpmService {
  public readonly registryChanges: Observable<RepoPermissionInfo>;

  private activeRegistry: RepoPermissionInfo;

  private readonly apiBaseUrl: string = environment.apiBaseUrl;
  private readonly registrySub = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly http: HttpClient,
    private readonly errorHandlerService: ErrorHandlerService,
  ) {
    this.registryChanges = this.registrySub.asObservable();
  }

  public getRegistry(registryName: string): Observable<RepoPermissionInfo> {
    const url = `${this.apiBaseUrl}/api/repos/${registryName}/permissions`;

    return new Observable<RepoPermissionInfo>((observer: Subscriber<RepoPermissionInfo>) => {
      this.http.get<RestResponse<RepoPermissionInfo>>(url).subscribe(
        (res: RestResponse<RepoPermissionInfo>) => {
          this.activeRegistry = res.data;
          this.registrySub.next(res.data);

          observer.next(res.data);
          observer.complete();
        },
        (err: HttpErrorResponse) => {
          observer.error(this.errorHandlerService.handle(err));
          observer.complete();
        },
      );
    });
  }

  public async fetchRegistrySettings(): Promise<RepositorySettingsInfo> {
    return new Promise<RepositorySettingsInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRegistry.repoName}/settings`;

      this.http
        .get<RestResponse<RepositorySettingsInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepositorySettingsInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepoSettings(repoSettingsForm: RepoSettingsForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRegistry.repoName}/settings`;

      this.http
        .put<RestResponse<void>>(url, repoSettingsForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRegistryName(repoNameForm: RepoNameForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRegistry.repoName}/name`;

      this.http
        .patch<RestResponse<void>>(url, repoNameForm)
        .toPromise()
        .then(() => {
          if (this.activeRegistry) {
            this.activeRegistry.repoName = repoNameForm.name;
            this.registrySub.next(this.activeRegistry);
          }
          resolve();
        })
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRegistryDescription(registryNameForm: RepoDescriptionForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRegistry.repoName}/description`;

      this.http
        .patch<RestResponse<void>>(url, registryNameForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteRegistry(repo: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${repo}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async createRegistry(registryCreateForm: RepoForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/NPM`;

      this.http
        .post<RestResponse<void>>(url, registryCreateForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRegistries(): Promise<RepoListItem[]> {
    return new Promise<RepoListItem[]>((resolve, reject) => {
      const url = `${this.apiBaseUrl + '/api/repos/NPM/info'}`;

      this.http
        .get<RestResponse<RepoListItem[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListItem[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRegistryPackagesLikeScope(
    scopeName: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<PackageListItem>> {
    return new Promise((resolve, reject) => {
      let params = new HttpParams()
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      if (scopeName) {
        params = new HttpParams()
          .set('scope', scopeName)
          .set('page', pageIndex.toString())
          .set('sort', `${sortOption.column},${sortOption.type}`)
          .set('size', pageSize.toString());
      }

      const url = `${this.apiBaseUrl}/api/npm/packages/${this.activeRegistry.repoName}`;

      this.http
        .get<RestResponse<PagedData<PackageListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRegistryPackagesLikeName(
    scopeName: string,
    name: string,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<PackageListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('name', name)
        .set('page', pageIndex.toString())
        .set('sort', 'updatedAt,DESC')
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/npm/packages/${this.activeRegistry.repoName}/${scopeName}`;

      this.http
        .get<RestResponse<PagedData<PackageListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRegistryUsage(): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRegistry.repoName}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deletePackage(packageName: string, scopeName: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      let url = `${this.apiBaseUrl}/api/npm/packages/${this.activeRegistry.repoName}`;

      if (scopeName) {
        url = url + '/' + scopeName;
      }

      url = url + '/' + packageName;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deletePackageVersion(packageName: string, scopeName: string, versionName: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      let url = `${this.apiBaseUrl}/api/npm/packages/${this.activeRegistry.repoName}`;

      if (scopeName) {
        url = url + '/' + scopeName;
      }

      url = url + '/' + packageName + '/versions/' + versionName;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchPackageVersion(
    packageName: string,
    scopeName: string,
    versionName: string,
  ): Promise<PackageVersionInfo> {
    return new Promise((resolve, reject) => {
      let url = `${this.apiBaseUrl}/api/npm/packages/${this.activeRegistry.repoName}`;

      if (scopeName) {
        url = url + '/' + scopeName;
      }

      url = url + '/' + packageName;

      if (versionName) {
        url = url + '/versions/' + versionName;
      }

      this.http
        .get<RestResponse<PackageVersionInfo>>(url)
        .toPromise()
        .then((res: RestResponse<PackageVersionInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchPackageVersions(
    packageName: string,
    scopeName: string,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<PackageVersionListItem>> {
    return new Promise<PagedData<PackageVersionListItem>>((resolve, reject) => {
      const params = new HttpParams()
        .set('page', pageIndex.toString())
        .set('sort', 'createdAt,DESC')
        .set('size', pageSize.toString());

      let url = this.apiBaseUrl + '/api/npm/packages/' + this.activeRegistry.repoName;

      if (scopeName) {
        url = url + '/' + scopeName;
      }

      url = url + '/package/' + packageName + '/versions';

      this.http
        .get<RestResponse<PagedData<PackageVersionListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageVersionListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchPackageVersionsLikeVersion(
    packageName: string,
    scopeName: string,
    versionName: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<PackageVersionListItem>> {
    return new Promise<PagedData<PackageVersionListItem>>((resolve, reject) => {
      const params = new HttpParams()
        .set('version', versionName)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      let url = this.apiBaseUrl + '/api/npm/packages/' + this.activeRegistry.repoName;

      if (scopeName) {
        url = url + '/' + scopeName;
      }

      url = url + '/package/' + packageName + '/versions';

      this.http
        .get<RestResponse<PagedData<PackageVersionListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageVersionListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchPackageTags(packageName: string, scopeName: string): Promise<PackageDistributionTagMapListItem[]> {
    return new Promise<PackageDistributionTagMapListItem[]>((resolve, reject) => {
      let url = `${this.apiBaseUrl}/api/npm/packages/${this.activeRegistry.repoName}`;

      if (scopeName) {
        url = url + '/' + scopeName;
      }

      url = url + '/package/' + packageName + '/tags';

      this.http
        .get<RestResponse<PackageDistributionTagMapListItem[]>>(url)
        .toPromise()
        .then((res: RestResponse<PackageDistributionTagMapListItem[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRegistryPackagesFilterByScopeLikeName(
    name: string,
    sortOption: Sort,
    scopeName: string,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<PackageListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('name', name)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/npm/packages/${this.activeRegistry.repoName}/scope/${scopeName}`;

      this.http
        .get<RestResponse<PagedData<PackageListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRegistryPackagesFilterByNoScopeLikeName(
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<PackageListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('name', name)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/npm/packages/${this.activeRegistry.repoName}/scope`;

      this.http
        .get<RestResponse<PagedData<PackageListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRegistryPackages(pageIndex: number, pageSize: number): Promise<PagedData<PackageListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('page', pageIndex.toString())
        .set('sort', 'updatedAt,DESC')
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/npm/packages/` + `${this.activeRegistry.repoName}`;

      this.http
        .get<RestResponse<PagedData<PackageListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getDeployTokens(pageNumber: number, pageSize: number): Promise<PagedData<DeployTokenInfo>> {
    const params = new HttpParams().set('page', pageNumber.toString()).set('size', pageSize.toString());

    const url = `${this.apiBaseUrl}/api/repos/` + this.activeRegistry.repoName + '/deploy-tokens';

    return new Promise<PagedData<DeployTokenInfo>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<DeployTokenInfo>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<DeployTokenInfo>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async rotateDeployToken(tokenUuid: string): Promise<string> {
    const url = `${this.apiBaseUrl}/api/repos/${this.activeRegistry.repoName}/deploy-tokens/` + tokenUuid;

    return new Promise<string>((resolve, reject) => {
      this.http
        .put(url, {})
        .toPromise()
        .then((res: RestResponse<string>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async createDeployToken(form: DeployTokenForm): Promise<TokenCreateInfo> {
    const url = `${this.apiBaseUrl}/api/repos/${this.activeRegistry.repoName}/deploy-tokens`;

    return new Promise<TokenCreateInfo>((resolve, reject) => {
      this.http
        .post(url, form)
        .toPromise()
        .then((res: RestResponse<TokenCreateInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async revokeDeployToken(tokenId: string): Promise<void> {
    const url = `${this.apiBaseUrl}/api/repos/${this.activeRegistry.repoName}/deploy-tokens/` + tokenId;

    return new Promise((resolve, reject) => {
      this.http
        .delete(url, {})
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }
}
