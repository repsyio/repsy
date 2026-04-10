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

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';

import { environment } from '../../../../environments/environment';
import { ErrorHandlerService } from '../../../shared/error-handler/error-handler.service';
import { RepoListInfo } from '../dto/repo/repo-list-info';
import { RepoUsageInfo } from '../dto/repo-usage-info';
import { RestResponse } from '../dto/rest-response';
import { TotalUsageInfo } from '../dto/total-usage-info';

@Injectable({
  providedIn: 'root',
})
export class StatsService {
  private readonly apiBaseUrl = environment.apiBaseUrl;

  constructor(
    private readonly http: HttpClient,
    private readonly errorHandlerService: ErrorHandlerService,
  ) {}

  public async getMavenRepoCount(): Promise<number> {
    const url = `${this.apiBaseUrl}/api/mvn/repos/count`;

    return new Promise<number>((resolve, reject) => {
      return this.http
        .get<RestResponse<number>>(url)
        .toPromise()
        .then((res: RestResponse<number>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getNpmRegistryCount(): Promise<number> {
    const url = `${this.apiBaseUrl}/api/npm/registries/count`;

    return new Promise<number>((resolve, reject) => {
      return this.http
        .get<RestResponse<number>>(url)
        .toPromise()
        .then((res: RestResponse<number>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getPypiRepoCount(): Promise<number> {
    const url = `${this.apiBaseUrl}/api/pypi/repos/count`;

    return new Promise<number>((resolve, reject) => {
      return this.http
        .get<RestResponse<number>>(url)
        .toPromise()
        .then((res: RestResponse<number>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getDockerRepoCount(): Promise<number> {
    const url = `${this.apiBaseUrl}/api/docker/repos/count`;

    return new Promise<number>((resolve, reject) => {
      return this.http
        .get<RestResponse<number>>(url)
        .toPromise()
        .then((res: RestResponse<number>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getTotalUsage(): Promise<TotalUsageInfo> {
    return new Promise<TotalUsageInfo>((resolve, reject) => {
      return this.http
        .get<RestResponse<TotalUsageInfo>>(`${this.apiBaseUrl}/api/usages`)
        .toPromise()
        .then((res: RestResponse<TotalUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchMavenRepositoryUsage(repo: string): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/mvn/repos/${repo}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchNpmRepositoryUsage(repo: string): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/npm/registries/${repo}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchPypiRepositoryUsage(repo: string): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/pypi/repos/${repo}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchDockerRepositoryUsage(repo: string): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/docker/repos/${repo}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getMavenRepoInfo(): Promise<RepoListInfo[]> {
    const url = `${this.apiBaseUrl}/api/mvn/repos`;

    return new Promise<RepoListInfo[]>((resolve, reject) => {
      return this.http
        .get<RestResponse<RepoListInfo[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListInfo[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getNpmRepoInfo(): Promise<RepoListInfo[]> {
    const url = `${this.apiBaseUrl}/api/npm/registries`;

    return new Promise<RepoListInfo[]>((resolve, reject) => {
      return this.http
        .get<RestResponse<RepoListInfo[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListInfo[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getPypiRepoInfo(): Promise<RepoListInfo[]> {
    const url = `${this.apiBaseUrl}/api/pypi/repos`;

    return new Promise<RepoListInfo[]>((resolve, reject) => {
      return this.http
        .get<RestResponse<RepoListInfo[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListInfo[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getDockerRepoInfo(): Promise<RepoListInfo[]> {
    const url = `${this.apiBaseUrl}/api/docker/repos`;

    return new Promise<RepoListInfo[]>((resolve, reject) => {
      return this.http
        .get<RestResponse<RepoListInfo[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListInfo[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getGolangRepoCount(): Promise<number> {
    const url = `${this.apiBaseUrl}/api/go/repos/count`;

    return new Promise<number>((resolve, reject) => {
      return this.http
        .get<RestResponse<number>>(url)
        .toPromise()
        .then((res: RestResponse<number>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async fetchGolangRepositoryUsage(repo: string): Promise<RepoUsageInfo> {
    return new Promise<RepoUsageInfo>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/go/repos/${repo}/usage`;

      this.http
        .get<RestResponse<RepoUsageInfo>>(url)
        .toPromise()
        .then((res: RestResponse<RepoUsageInfo>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  public async getGolangRepoInfo(): Promise<RepoListInfo[]> {
    const url = `${this.apiBaseUrl}/api/go/repos/info`;

    return new Promise<RepoListInfo[]>((resolve, reject) => {
      return this.http
        .get<RestResponse<RepoListInfo[]>>(url)
        .toPromise()
        .then((res: RestResponse<RepoListInfo[]>) => resolve(res.data))
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }
}
