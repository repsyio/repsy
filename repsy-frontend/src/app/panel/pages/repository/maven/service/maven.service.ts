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
import { SignatureForm } from '../../repo-settings/signature/dto/signature-form';
import { SignatureItem } from '../../repo-settings/signature/dto/signature-item';
import { ArtifactListItem } from '../dto/artifact-list-item';
import { ArtifactVersionInfo } from '../dto/artifact-version-info';
import { ArtifactVersionListItem } from '../dto/artifact-version-list-item';
import { DeletedItem } from '../dto/deleted-item';
import { FsItemInfo } from '../dto/fs-item-info';
import { MavenRepoSettingsInfo } from '../dto/maven-repo-settings-info';

@Injectable({
  providedIn: 'root',
})
export class MavenService {
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
        .post<RestResponse<null>>(`${this.apiBaseUrl}/api/mvn/repo`, form)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteArtifact(groupName: string, artifactName: string): Promise<DeletedItem> {
    const url = `${this.apiBaseUrl}/api/mvn/artifacts/${this.activeRepo.repoName}/${groupName}/${artifactName}`;

    return new Promise<DeletedItem>((resolve, reject) => {
      return this.http
        .delete<RestResponse<DeletedItem>>(url)
        .toPromise()
        .then((res: RestResponse<DeletedItem>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteGroup(groupName: string): Promise<DeletedItem> {
    const url = `${this.apiBaseUrl}/api/mvn/artifacts/` + `${this.activeRepo.repoName}/${groupName}`;

    return new Promise<DeletedItem>((resolve, reject) => {
      return this.http
        .delete<RestResponse<DeletedItem>>(url)
        .toPromise()
        .then((res: RestResponse<DeletedItem>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteRepository(repo: string): Promise<void> {
    const url = `${this.apiBaseUrl}/api/mvn/repo/${repo}`;

    return new Promise<void>((resolve, reject) => {
      return this.http
        .delete<RestResponse<null>>(url)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async deleteVersion(groupName: string, artifactName: string, versionName: string): Promise<DeletedItem> {
    const url =
      `${this.apiBaseUrl}/api/mvn/artifacts/` +
      this.activeRepo.repoName +
      '/' +
      `${groupName}/${artifactName}/versions/${versionName}`;

    return new Promise<DeletedItem>((resolve, reject) => {
      return this.http
        .delete<RestResponse<DeletedItem>>(url)
        .toPromise()
        .then((res: RestResponse<DeletedItem>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchRepos(): Promise<RepoListItem[]> {
    return new Promise<RepoListItem[]>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/mvn/repo`;

      this.http
        .get<RestResponse<RepoListItem[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListItem[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getArtifactVersion(
    groupName: string,
    artifactName: string,
    versionName: string,
  ): Promise<ArtifactVersionInfo> {
    const url =
      `${this.apiBaseUrl}/api/mvn/artifacts/` +
      this.activeRepo.repoName +
      '/' +
      `${groupName}/${artifactName}/version/${versionName}`;

    return new Promise<ArtifactVersionInfo>((resolve, reject) => {
      this.http
        .get<RestResponse<ArtifactVersionInfo>>(url)
        .toPromise()
        .then((res: RestResponse<ArtifactVersionInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getArtifactVersions(
    groupName: string,
    artifactName: string,
    pageNumber: number,
    pageSize: number,
  ): Promise<PagedData<ArtifactVersionListItem>> {
    const params = new HttpParams()
      .set('page', pageNumber.toString())
      .set('size', pageSize.toString())
      .set('sort', 'versionName');

    const url =
      `${this.apiBaseUrl}/api/mvn/artifacts/` +
      this.activeRepo.repoName +
      '/' +
      `${groupName}/${artifactName}/versions`;

    return new Promise<PagedData<ArtifactVersionListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ArtifactVersionListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ArtifactVersionListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getArtifactVersionsLikeVersion(
    versionName: string,
    sortOption: Sort,
    groupName: string,
    artifactName: string,
    pageNumber: number,
    pageSize: number,
  ): Promise<PagedData<ArtifactVersionListItem>> {
    const params = new HttpParams()
      .set('version', versionName)
      .set('page', pageNumber.toString())
      .set('size', pageSize.toString())
      .set('sort', `${sortOption.column},${sortOption.type}`);

    const url =
      `${this.apiBaseUrl}/api/mvn/artifacts/` +
      this.activeRepo.repoName +
      '/' +
      `${groupName}/${artifactName}/versions/search`;

    return new Promise<PagedData<ArtifactVersionListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ArtifactVersionListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ArtifactVersionListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getGroupArtifacts(
    groupName: string,
    pageNumber: number,
    pageSize: number,
  ): Promise<PagedData<ArtifactListItem>> {
    const params = new HttpParams()
      .set('page', pageNumber + '')
      .set('size', pageSize.toString())
      .set('sort', 'artifactName');

    const url = `${this.apiBaseUrl}/api/mvn/artifacts/` + `${this.activeRepo.repoName}/${groupName}`;

    return new Promise<PagedData<ArtifactListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ArtifactListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ArtifactListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getGroupArtifactsLikeArtifactName(
    groupName: string,
    artifactName: string,
    sortOption: Sort,
    pageNumber: number,
    pageSize: number,
  ): Promise<PagedData<ArtifactListItem>> {
    const params = new HttpParams()
      .set('artifactName', artifactName)
      .set('page', pageNumber + '')
      .set('size', pageSize.toString())
      .set('sort', `${sortOption.column},${sortOption.type}`);

    const url = `${this.apiBaseUrl}/api/mvn/artifacts/` + `${this.activeRepo.repoName}/${groupName}/search`;

    return new Promise<PagedData<ArtifactListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ArtifactListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ArtifactListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getPathContent(path: string): Promise<FsItemInfo[]> {
    const params = new HttpParams().set('path', path);

    const url = `${this.apiBaseUrl}/api/mvn/repo/` + `${this.activeRepo.repoName}/content`;

    return new Promise<FsItemInfo[]>((resolve, reject) => {
      this.http
        .get<RestResponse<FsItemInfo[]>>(url, { params })
        .toPromise()
        .then((res: RestResponse<FsItemInfo[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getRepoArtifacts(pageNumber: number, pageSize: number): Promise<PagedData<ArtifactListItem>> {
    const params = new HttpParams()
      .set('page', pageNumber.toString())
      .set('sort', 'groupName,DESC')
      .set('size', pageSize.toString());

    const url = `${this.apiBaseUrl}/api/mvn/artifacts/` + `${this.activeRepo.repoName}`;

    return new Promise<PagedData<ArtifactListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ArtifactListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ArtifactListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getRepoArtifactsLikeGroupName(
    groupName: string,
    sortOption: Sort,
    pageNumber: number,
    pageSize: number,
  ): Promise<PagedData<ArtifactListItem>> {
    const params = new HttpParams()
      .set('groupName', groupName)
      .set('page', pageNumber.toString())
      .set('sort', `${sortOption.column},${sortOption.type}`)
      .set('size', pageSize.toString());

    const url = `${this.apiBaseUrl}/api/mvn/artifacts/` + `${this.activeRepo.repoName}/search`;

    return new Promise<PagedData<ArtifactListItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<ArtifactListItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<ArtifactListItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getRepoSettings(): Promise<MavenRepoSettingsInfo> {
    const url = `${this.apiBaseUrl}/api/mvn/repo/` + `${this.activeRepo.repoName}/settings`;

    return new Promise<MavenRepoSettingsInfo>((resolve, reject) => {
      this.http
        .get<RestResponse<MavenRepoSettingsInfo>>(url)
        .toPromise()
        .then((res: RestResponse<MavenRepoSettingsInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getRepoUsage(): Promise<RepoUsageInfo> {
    const url = `${this.apiBaseUrl}/api/mvn/repo/` + `${this.activeRepo.repoName}/usage`;

    return new Promise<RepoUsageInfo>((resolve, reject) => {
      return this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepositoryName(repositoryNameForm: RepoNameForm): Promise<void> {
    const url = `${this.apiBaseUrl}/api/mvn/repo/` + `${this.activeRepo.repoName}/name`;

    return new Promise<void>((resolve, reject) => {
      return this.http
        .patch<RestResponse<null>>(url, repositoryNameForm)
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

  public async updateRepoDescription(repoDescriptionForm: RepoDescriptionForm): Promise<void> {
    const url = `${this.apiBaseUrl}/api/mvn/repo/` + `${this.activeRepo.repoName}/description`;

    return new Promise<void>((resolve, reject) => {
      return this.http
        .patch<RestResponse<null>>(url, repoDescriptionForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async updateRepoSettings(form: RepoSettingsForm): Promise<void> {
    const url = `${this.apiBaseUrl}/api/mvn/repo/${this.activeRepo.repoName}/settings`;

    return new Promise<void>((resolve, reject) => {
      return this.http
        .put<RestResponse<null>>(url, form)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public getRepoPermission(repoName: string): Observable<RepoPermissionInfo> {
    const url = `${this.apiBaseUrl}/api/mvn/repo/${repoName}/permission`;

    return new Observable<RepoPermissionInfo>((subscriber: Subscriber<RepoPermissionInfo>) => {
      this.http.get<RestResponse<RepoPermissionInfo>>(url).subscribe(
        (res: RestResponse<RepoPermissionInfo>) => {
          this.activeRepo = res.data;
          this.repoSub.next(res.data);

          subscriber.next(res.data);
          subscriber.complete();
        },
        (res: HttpErrorResponse) => {
          subscriber.error(this.errorHandlerService.handle(res));
          subscriber.complete();
        },
      );
    });
  }

  public async getDeployTokens(pageNumber: number, pageSize: number): Promise<PagedData<DeployTokenInfo>> {
    const params = new HttpParams().set('page', pageNumber.toString()).set('size', pageSize.toString());

    const url = `${this.apiBaseUrl}/api/mvn/deploy-tokens/` + this.activeRepo.repoName;

    return new Promise<PagedData<DeployTokenInfo>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<DeployTokenInfo>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<DeployTokenInfo>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async rotateDeployToken(tokenUuid: string): Promise<string> {
    const url = `${this.apiBaseUrl}/api/mvn/deploy-tokens/${this.activeRepo.repoName}/` + tokenUuid;

    return new Promise<string>((resolve, reject) => {
      this.http
        .put(url, {})
        .toPromise()
        .then((res: RestResponse<string>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async createDeployToken(form: DeployTokenForm): Promise<TokenCreateInfo> {
    const url = `${this.apiBaseUrl}/api/mvn/deploy-tokens/${this.activeRepo.repoName}`;

    return new Promise<TokenCreateInfo>((resolve, reject) => {
      this.http
        .post(url, form)
        .toPromise()
        .then((res: RestResponse<TokenCreateInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async revokeDeployToken(tokenId: string): Promise<void> {
    const url = `${this.apiBaseUrl}/api/mvn/deploy-tokens/${this.activeRepo.repoName}/` + tokenId;

    return new Promise((resolve, reject) => {
      this.http
        .delete(url, {})
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  fetchKeyStores(pageIndex: number, pageSize: number) {
    const params = new HttpParams().set('page', pageIndex.toString()).set('size', pageSize.toString());

    const url = `${this.apiBaseUrl}/api/mvn/key-stores/${this.activeRepo.repoName}`;

    return new Promise<PagedData<SignatureItem>>((resolve, reject) => {
      this.http
        .get<RestResponse<PagedData<SignatureItem>>>(url, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<SignatureItem>>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  createKeyStore(form: SignatureForm) {
    const url = `${this.apiBaseUrl}/api/mvn/key-stores/${this.activeRepo.repoName}`;

    return new Promise<void>((resolve, reject) => {
      this.http
        .post(url, form)
        .toPromise()
        .then((res: RestResponse<void>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  deleteKeyStore(uuid: string) {
    const url = `${this.apiBaseUrl}/api/mvn/key-stores/${this.activeRepo.repoName}/${uuid}`;

    return new Promise<void>((resolve, reject) => {
      this.http
        .delete(url)
        .toPromise()
        .then((res: RestResponse<void>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }
}
