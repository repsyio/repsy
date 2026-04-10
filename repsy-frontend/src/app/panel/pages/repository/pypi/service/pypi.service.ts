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
import { DeployTokenInfo } from '../../repo-settings/deploy-token/dto/deploy-token-info';
import { TokenCreateInfo } from '../../repo-settings/deploy-token/dto/token-create-info';
import { DeployTokenForm } from '../../repo-settings/deploy-token/form/deploy-token-form';
import { PackageListItem } from '../dto/package-list-item';
import { ReleaseInfo } from '../dto/release-info';
import { ReleaseListItem } from '../dto/release-list-item';
import { RepositorySettingsInfo } from '../dto/repository-settings-info';

@Injectable({
  providedIn: 'root',
})
export class PypiService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private activeRepo: RepoPermissionInfo;

  private readonly apiBaseUrl: string = environment.apiBaseUrl;
  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly http: HttpClient,
    private readonly errorHandlerService: ErrorHandlerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  public selectRepository(repoName: string): Observable<RepoPermissionInfo> {
    const url = `${this.apiBaseUrl}/api/pypi/repos/${repoName}/permissions`;

    return new Observable<RepoPermissionInfo>((subscriber: Subscriber<RepoPermissionInfo>) => {
      this.http.get<RestResponse<RepoPermissionInfo>>(url).subscribe({
        next: (res: RestResponse<RepoPermissionInfo>) => {
          this.activeRepo = res.data;
          this.repoSubject.next(res.data);

          subscriber.next(res.data);
          subscriber.complete();
        },
        error: (err: HttpErrorResponse) => {
          subscriber.error(this.errorHandlerService.handle(err));
          subscriber.complete();
        },
      });
    });
  }

  public async createRepository(repositoryCreateForm: RepoForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/repos`;

      this.http
        .post<RestResponse<void>>(url, repositoryCreateForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositoryUsage(): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/repos/${this.activeRepo.repoName}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositories(): Promise<RepoListItem[]> {
    return new Promise<RepoListItem[]>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/repos`;

      this.http
        .get<RestResponse<RepoListItem[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListItem[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositorySettings(): Promise<RepositorySettingsInfo> {
    return new Promise<RepositorySettingsInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/repos/${this.activeRepo.repoName}/settings`;

      this.http
        .get<RestResponse<RepositorySettingsInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepositorySettingsInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepoSettings(repoSettingsForm: RepoSettingsForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/repos/${this.activeRepo.repoName}/settings`;

      this.http
        .put<RestResponse<void>>(url, repoSettingsForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepositoryName(repositoryNameForm: RepoNameForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/repos/${this.activeRepo.repoName}/name`;

      this.http
        .patch<RestResponse<void>>(url, repositoryNameForm)
        .toPromise()
        .then(() => {
          if (this.activeRepo) {
            this.activeRepo.repoName = repositoryNameForm.name;
            this.repoSubject.next(this.activeRepo);
          }
          resolve();
        })
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepoDescription(repositoryDescriptionForm: RepoDescriptionForm): Promise<void> {
    const url = `${this.apiBaseUrl}/api/pypi/repos/${this.activeRepo.repoName}/description`;

    return new Promise<void>((resolve, reject) => {
      return this.http
        .patch<RestResponse<null>>(url, repositoryDescriptionForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteRepository(repo: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/repos/${repo}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositoryPackages(pageIndex: number, pageSize: number): Promise<PagedData<PackageListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('page', pageIndex.toString())
        .set('sort', 'updatedAt,DESC')
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/pypi/packages/${this.activeRepo.repoName}`;

      this.http
        .get<RestResponse<PagedData<PackageListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositoryPackagesLikeName(
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

      const url = `${this.apiBaseUrl}/api/pypi/packages/${this.activeRepo.repoName}`;

      this.http
        .get<RestResponse<PagedData<PackageListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<PackageListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchPackageReleases(
    packageName: string,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<ReleaseListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('page', pageIndex.toString())
        .set('sort', 'createdAt,DESC')
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/pypi/packages/${this.activeRepo.repoName}/${packageName}/releases`;

      this.http
        .get<RestResponse<PagedData<ReleaseListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ReleaseListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchPackageReleasesLikeName(
    packageName: string,
    version: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<ReleaseListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('version', version)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/pypi/packages/${this.activeRepo.repoName}/${packageName}/releases`;

      this.http
        .get<RestResponse<PagedData<ReleaseListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ReleaseListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public deletePackage(packageName: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/packages/${this.activeRepo.repoName}/${packageName}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRelease(packageName: string, release: string): Promise<ReleaseInfo> {
    return new Promise<ReleaseInfo>((resolve, reject) => {
      const url =
        this.apiBaseUrl + '/api/pypi/packages/' + this.activeRepo.repoName + '/' + packageName + '/releases/' + release;

      this.http
        .get<RestResponse<ReleaseInfo>>(url)
        .toPromise()
        .then((res: RestResponse<ReleaseInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public deleteRelease(packageName: string, releaseVersion: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url =
        this.apiBaseUrl +
        '/api/pypi/packages/' +
        this.activeRepo.repoName +
        '/' +
        packageName +
        '/releases/' +
        releaseVersion;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getDeployTokens(pageNumber: number, pageSize: number): Promise<PagedData<DeployTokenInfo>> {
    const params = new HttpParams().set('page', pageNumber.toString()).set('size', pageSize.toString());

    const url = `${this.apiBaseUrl}/api/pypi/deploy-tokens/` + this.activeRepo.repoName;

    return new Promise<PagedData<DeployTokenInfo>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<DeployTokenInfo>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<DeployTokenInfo>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async rotateDeployToken(tokenUuid: string): Promise<string> {
    const url = `${this.apiBaseUrl}/api/pypi/deploy-tokens/${this.activeRepo.repoName}/` + tokenUuid;

    return new Promise<string>((resolve, reject) => {
      this.http
        .put(url, {})
        .toPromise()
        .then((res: RestResponse<string>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async createDeployToken(form: DeployTokenForm): Promise<TokenCreateInfo> {
    const url = `${this.apiBaseUrl}/api/pypi/deploy-tokens/${this.activeRepo.repoName}`;

    return new Promise<TokenCreateInfo>((resolve, reject) => {
      this.http
        .post(url, form)
        .toPromise()
        .then((res: RestResponse<TokenCreateInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async revokeDeployToken(tokenUuid: string): Promise<void> {
    const url = `${this.apiBaseUrl}/api/pypi/deploy-tokens/${this.activeRepo.repoName}/` + tokenUuid;

    return new Promise((resolve, reject) => {
      this.http
        .delete(url, {})
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }
}
