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
import { ImageListItem } from '../dto/image-list-item';
import { ManifestListItem } from '../dto/manifest-list-item';
import { RepoSettingsInfo } from '../dto/repo-settings-info';
import { TagInfo } from '../dto/tag-info';
import { TagListItem } from '../dto/tag-list-item';
import { RepositorySettingsInfo } from '../../pypi/dto/repository-settings-info';

@Injectable({
  providedIn: 'root',
})
export class DockerService {
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
    const url = `${this.apiBaseUrl}/api/repos/${repoName}/permissions`;

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
      const url = `${this.apiBaseUrl}/api/repos/DOCKER`;

      this.http
        .post<RestResponse<void>>(url, repoForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositoryUsage(): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositories(): Promise<RepoListItem[]> {
    return new Promise<RepoListItem[]>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/DOCKER/info`;

      this.http
        .get<RestResponse<RepoListItem[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListItem[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositorySettings(): Promise<RepositorySettingsInfo> {
    return new Promise<RepositorySettingsInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/settings`;

      this.http
        .get<RestResponse<RepositorySettingsInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepositorySettingsInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepoSettings(repoSettingsForm: RepoSettingsForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/settings`;
      this.http
        .put<RestResponse<void>>(url, repoSettingsForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepositoryName(repositoryNameForm: RepoNameForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/name`;

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

  public async updateRepositoryDescription(repoDescriptionForm: RepoDescriptionForm): Promise<void> {
    const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/description`;

    return new Promise<void>((resolve, reject) => {
      return this.http
        .patch<RestResponse<null>>(url, repoDescriptionForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteRepository(repo: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/repos/${repo}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepositoryImagesLikeName(
    imageName: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<ImageListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('name', imageName)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/docker/images/${this.activeRepo.repoName}`;

      this.http
        .get<RestResponse<PagedData<ImageListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ImageListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchImageTagsLikeName(
    tagName: string,
    sortOption: Sort,
    imageName: string,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<TagListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('name', tagName)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/docker/images/${this.activeRepo.repoName}/${imageName}/tags`;

      this.http
        .get<RestResponse<PagedData<TagListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<TagListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchManifestsLikeName(
    manifestName: string,
    sortOption: Sort,
    imageName: string,
    tagName: string,
    pageIndex: number,
    pageSize: number,
  ): Promise<PagedData<ManifestListItem>> {
    return new Promise((resolve, reject) => {
      const params = new HttpParams()
        .set('name', manifestName)
        .set('page', pageIndex.toString())
        .set('sort', `${sortOption.column},${sortOption.type}`)
        .set('size', pageSize.toString());

      const url = `${this.apiBaseUrl}/api/docker/images/${this.activeRepo.repoName}/${imageName}/tags/${tagName}/manifests`;

      this.http
        .get<RestResponse<PagedData<ManifestListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ManifestListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public deleteImage(imageName: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/docker/images/${this.activeRepo.repoName}/${imageName}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchTag(imageName: string, tagName: string): Promise<TagInfo> {
    return new Promise<TagInfo>((resolve, reject) => {
      const url =
        this.apiBaseUrl + '/api/docker/images/' + this.activeRepo.repoName + '/' + imageName + '/tags/' + tagName;

      this.http
        .get<RestResponse<TagInfo>>(url)
        .toPromise()
        .then((res: RestResponse<TagInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public deleteTag(imageName: string, tagName: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/docker/images/${this.activeRepo.repoName}/${imageName}/tags/${tagName}`;

      this.http
        .delete<RestResponse<void>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchManifestText(imageName: string, digest: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/docker/images/${this.activeRepo.repoName}/${imageName}/manifests/${digest}`;

      this.http
        .get<RestResponse<string>>(url)
        .toPromise()
        .then((res: RestResponse<string>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchConfigText(imageName: string, digest: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/docker/images/${this.activeRepo.repoName}/${imageName}/configs/${digest}`;

      this.http
        .get<RestResponse<string>>(url)
        .toPromise()
        .then((res: RestResponse<string>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getDeployTokens(pageNumber: number, pageSize: number): Promise<PagedData<DeployTokenInfo>> {
    const params = new HttpParams().set('page', pageNumber.toString()).set('size', pageSize.toString());

    const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/deploy-tokens`;

    return new Promise<PagedData<DeployTokenInfo>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<DeployTokenInfo>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<DeployTokenInfo>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async rotateDeployToken(tokenId: string): Promise<string> {
    const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/deploy-tokens/` + tokenId;

    return new Promise<string>((resolve, reject) => {
      this.http
        .put(url, {})
        .toPromise()
        .then((res: RestResponse<string>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async createDeployToken(form: DeployTokenForm): Promise<TokenCreateInfo> {
    const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/deploy-tokens`;

    return new Promise<TokenCreateInfo>((resolve, reject) => {
      this.http
        .post(url, form)
        .toPromise()
        .then((res: RestResponse<TokenCreateInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async revokeDeployToken(tokenId: string): Promise<void> {
    const url = `${this.apiBaseUrl}/api/repos/${this.activeRepo.repoName}/deploy-tokens/` + tokenId;

    return new Promise((resolve, reject) => {
      this.http
        .delete(url, {})
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }
}
