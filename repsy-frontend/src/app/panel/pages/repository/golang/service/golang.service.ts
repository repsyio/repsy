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
import { RepoUsageInfo } from '../../../../shared/dto/repo-usage-info';
import { RestResponse } from '../../../../shared/dto/rest-response';
import { DeployTokenInfo } from '../../repo-settings/deploy-token/dto/deploy-token-info';
import { RepositorySettingsInfo } from '../../pypi/dto/repository-settings-info';
import { TokenCreateInfo } from '../../repo-settings/deploy-token/dto/token-create-info';
import { DeployTokenForm } from '../../repo-settings/deploy-token/form/deploy-token-form';
import { Sort } from '../../../../shared/dto/sort';
import { ModuleInfo } from '../dto/module-info';
import { ModuleListItem } from '../dto/module-list-item';
import { ModuleVersionListItem } from '../dto/module-version-list-item';
import { RepoSettingsForm } from '../../../../shared/dto/repo/repo-settings-form';

@Injectable({
  providedIn: 'root',
})
export class GolangService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSub = new BehaviorSubject<RepoPermissionInfo>(null);

  private readonly apiBaseUrl = environment.apiBaseUrl;

  private activeRepo: RepoPermissionInfo;

  constructor(
    private readonly http: HttpClient,
    private readonly errorHandlerService: ErrorHandlerService,
  ) {
    this.repoChanges = this.repoSub.asObservable();
  }

  public async createRepository(form: RepoForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      return this.http
        .post<RestResponse<null>>(`${this.apiBaseUrl}/api/go/repos`, form)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public getRepository(repoName: string): Observable<RepoPermissionInfo> {
    const url = `${this.apiBaseUrl}/api/go/repos/${repoName}/permissions`;

    return new Observable<RepoPermissionInfo>((observer: Subscriber<RepoPermissionInfo>) => {
      this.http.get<RestResponse<RepoPermissionInfo>>(url).subscribe(
        (res: RestResponse<RepoPermissionInfo>) => {
          this.activeRepo = res.data;
          this.repoSub.next(res.data);
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

  public async deleteRepository(repoName: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      return this.http
        .delete<RestResponse<null>>(`${this.apiBaseUrl}/api/go/repos/${repoName}`)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositories(): Promise<RepoListItem[]> {
    return new Promise<RepoListItem[]>((resolve, reject) => {
      this.http
        .get<RestResponse<RepoListItem[]>>(`${this.apiBaseUrl}/api/go/repos/info`)
        .toPromise()
        .then((res: RestResponse<RepoListItem[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositorySettings(): Promise<RepositorySettingsInfo> {
    return new Promise<RepositorySettingsInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/go/repos/${this.activeRepo.repoName}/settings`;

      this.http
        .get<RestResponse<RepositorySettingsInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepositorySettingsInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositoryUsage(): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      this.http
        .get<RestResponse<RepoUsageInfo>>(`${this.apiBaseUrl}/api/go/repos/${this.activeRepo.repoName}/usage`)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepoDescription(form: RepoDescriptionForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.http
        .patch<RestResponse<void>>(`${this.apiBaseUrl}/api/go/repos/${this.activeRepo.repoName}/description`, form)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchModules(sortOption: Sort, pageIndex: number, pageSize: number): Promise<PagedData<ModuleListItem>> {
    const params = new HttpParams()
      .set('page', pageIndex.toString())
      .set('size', pageSize.toString())
      .set('sort', `${sortOption.column},${sortOption.type}`);

    return new Promise<PagedData<ModuleListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ModuleListItem>>>(`${this.apiBaseUrl}/api/go/modules/${this.activeRepo.repoName}`, {
          params,
        })
        .toPromise()
        .then((res: RestResponse<PagedData<ModuleListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async searchModules(
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<ModuleListItem>> {
    const params = new HttpParams()
      .set('search', search)
      .set('page', pageIndex.toString())
      .set('size', pageSize.toString())
      .set('sort', `${sortOption.column},${sortOption.type}`);

    return new Promise<PagedData<ModuleListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ModuleListItem>>>(
          `${this.apiBaseUrl}/api/go/modules/${this.activeRepo.repoName}/search`,
          { params },
        )
        .toPromise()
        .then((res: RestResponse<PagedData<ModuleListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteModule(modulePath: string): Promise<void> {
    const params = new HttpParams().set('modulePath', modulePath);

    return new Promise<void>((resolve, reject) => {
      this.http
        .delete<RestResponse<null>>(`${this.apiBaseUrl}/api/go/modules/${this.activeRepo.repoName}`, { params })
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchModuleVersions(
    modulePath: string,
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<ModuleVersionListItem>> {
    const params = new HttpParams()
      .set('modulePath', modulePath)
      .set('search', search)
      .set('page', pageIndex.toString())
      .set('size', pageSize.toString())
      .set('sort', `${sortOption.column},${sortOption.type}`);

    return new Promise<PagedData<ModuleVersionListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ModuleVersionListItem>>>(
          `${this.apiBaseUrl}/api/go/modules/${this.activeRepo.repoName}/versions`,
          { params },
        )
        .toPromise()
        .then((res: RestResponse<PagedData<ModuleVersionListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchModuleInfo(modulePath: string): Promise<ModuleInfo> {
    const params = new HttpParams().set('modulePath', modulePath);

    return new Promise<ModuleInfo>((resolve, reject) => {
      this.http
        .get<RestResponse<ModuleInfo>>(`${this.apiBaseUrl}/api/go/modules/${this.activeRepo.repoName}/info`, { params })
        .toPromise()
        .then((res: RestResponse<ModuleInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteModuleVersion(modulePath: string, version: string): Promise<void> {
    const params = new HttpParams().set('modulePath', modulePath).set('version', version);

    return new Promise<void>((resolve, reject) => {
      this.http
        .delete<RestResponse<null>>(`${this.apiBaseUrl}/api/go/modules/${this.activeRepo.repoName}/versions`, {
          params,
        })
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getDeployTokens(pageNumber: number, pageSize: number): Promise<PagedData<DeployTokenInfo>> {
    const params = new HttpParams().set('page', pageNumber.toString()).set('size', pageSize.toString());

    return new Promise<PagedData<DeployTokenInfo>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<DeployTokenInfo>>>(
          `${this.apiBaseUrl}/api/go/deploy-tokens/${this.activeRepo.repoName}`,
          { params },
        )
        .toPromise()
        .then((res: RestResponse<PagedData<DeployTokenInfo>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async createDeployToken(form: DeployTokenForm): Promise<TokenCreateInfo> {
    return new Promise<TokenCreateInfo>((resolve, reject) => {
      this.http
        .post(`${this.apiBaseUrl}/api/go/deploy-tokens/${this.activeRepo.repoName}`, form)
        .toPromise()
        .then((res: RestResponse<TokenCreateInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async revokeDeployToken(tokenUuid: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.http
        .delete(`${this.apiBaseUrl}/api/go/deploy-tokens/${this.activeRepo.repoName}/${tokenUuid}`)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async rotateDeployToken(tokenUuid: string): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      this.http
        .put(`${this.apiBaseUrl}/api/go/deploy-tokens/${this.activeRepo.repoName}/${tokenUuid}`, {})
        .toPromise()
        .then((res: RestResponse<string>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepoSettings(repoSettingsForm: RepoSettingsForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/go/repos/${this.activeRepo.repoName}/settings`;
      this.http
        .put<RestResponse<void>>(url, repoSettingsForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepositoryName(repositoryNameForm: RepoNameForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/go/repos/${this.activeRepo.repoName}/name`;

      this.http
        .patch<RestResponse<void>>(url, repositoryNameForm)
        .toPromise()
        .then(() => {
          if (this.activeRepo) {
            this.activeRepo.repoName = repositoryNameForm.name;
            this.repoSub.next(this.activeRepo);
          }
          resolve();
        })
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }
}
