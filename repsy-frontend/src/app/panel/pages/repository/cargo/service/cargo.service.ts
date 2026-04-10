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
import { CrateInfo } from '../dto/crate-info';
import { CrateListItem } from '../dto/crate-list-item';
import { CrateVersionListItem } from '../dto/crate-version-list-item';
import { CrateVersionInfo } from '../dto/crate-version-info';

@Injectable({
  providedIn: 'root',
})
export class CargoService {
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
    const url = `${this.apiBaseUrl}/api/cargo/repos/${repoName}/permissions`;

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

  public async createRepository(repoForm: RepoForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/repos`;

      this.http
        .post<RestResponse<void>>(url, repoForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositories(): Promise<RepoListItem[]> {
    return new Promise<RepoListItem[]>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/repos`;

      this.http
        .get<RestResponse<RepoListItem[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListItem[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositoryUsage(): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/repos/${this.activeRepo.repoName}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositorySettings(): Promise<RepoSettingsForm> {
    return new Promise<RepoSettingsForm>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/repos/${this.activeRepo.repoName}/settings`;

      this.http
        .get<RestResponse<RepoSettingsForm>>(url)
        .toPromise()
        .then((res: RestResponse<RepoSettingsForm>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepoSettings(repoSettingsForm: RepoSettingsForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/repos/${this.activeRepo.repoName}/settings`;
      this.http
        .put<RestResponse<void>>(url, repoSettingsForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepositoryName(repositoryNameForm: RepoNameForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/repos/${this.activeRepo.repoName}/name`;

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
    const url = `${this.apiBaseUrl}/api/cargo/repos/${this.activeRepo.repoName}/description`;

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
      const url = `${this.apiBaseUrl}/api/cargo/repos/${repo}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositoryCratesLikeName(
    query: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<CrateListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('query', query)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/cargo/crates/${this.activeRepo.repoName}`;

      this.http
        .get<RestResponse<PagedData<CrateListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<CrateListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchCrate(crateName: string): Promise<CrateInfo> {
    return new Promise<CrateInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/crates/${this.activeRepo.repoName}/${crateName}`;

      this.http
        .get<RestResponse<CrateInfo>>(url)
        .toPromise()
        .then((res: RestResponse<CrateInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchCrateVersion(crateName: string, version: string): Promise<CrateVersionInfo> {
    return new Promise<CrateVersionInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/crates/${this.activeRepo.repoName}/${crateName}/${version}`;

      this.http
        .get<RestResponse<CrateVersionInfo>>(url)
        .toPromise()
        .then((res: RestResponse<CrateVersionInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchCrateVersions(
    crateName: string,
    query: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<CrateVersionListItem>> {
    return new Promise<PagedData<CrateVersionListItem>>((resolve, reject) => {
      const params = new HttpParams()
        .set('query', query)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());
      const url = `${this.apiBaseUrl}/api/cargo/crates/${this.activeRepo.repoName}/${crateName}/versions`;

      this.http
        .get<RestResponse<PagedData<CrateVersionListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<CrateVersionListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public deleteCrate(crateName: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/crates/${this.activeRepo.repoName}/${crateName}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public deleteCrateVersion(crateName: string, version: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/crates/${this.activeRepo.repoName}/${crateName}/${version}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getDeployTokens(pageNumber: number, pageSize: number): Promise<PagedData<DeployTokenInfo>> {
    const params = new HttpParams().set('page', pageNumber.toString()).set('size', pageSize.toString());
    const url = `${this.apiBaseUrl}/api/cargo/deploy-tokens/${this.activeRepo.repoName}`;

    return new Promise<PagedData<DeployTokenInfo>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<DeployTokenInfo>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<DeployTokenInfo>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async rotateDeployToken(tokenId: string): Promise<string> {
    const url = `${this.apiBaseUrl}/api/cargo/deploy-tokens/${this.activeRepo.repoName}/${tokenId}`;

    return new Promise<string>((resolve, reject) => {
      this.http
        .put(url, {})
        .toPromise()
        .then((res: RestResponse<string>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async createDeployToken(form: DeployTokenForm): Promise<TokenCreateInfo> {
    const url = `${this.apiBaseUrl}/api/cargo/deploy-tokens/${this.activeRepo.repoName}`;

    return new Promise<TokenCreateInfo>((resolve, reject) => {
      this.http
        .post(url, form)
        .toPromise()
        .then((res: RestResponse<TokenCreateInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async revokeDeployToken(tokenId: string): Promise<void> {
    const url = `${this.apiBaseUrl}/api/cargo/deploy-tokens/${this.activeRepo.repoName}/${tokenId}`;

    return new Promise((resolve, reject) => {
      this.http
        .delete(url, {})
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }
}
