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
  HelmChartDetail,
  HelmChartListItem,
  HelmChartsApi,
  HelmChartSummary,
  HelmChartVersionItem,
  RepoPermissionInfo,
  ReposApi,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';

@Injectable({
  providedIn: 'root',
})
export class HelmService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly reposApi: ReposApi,
    private readonly helmChartsApi: HelmChartsApi,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public getRepository(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.reposApi.getRepoPermissions(repoName).pipe(tap((info) => this.repoSubject.next(info)));
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject.next(null);
  }

  public searchCharts(
    query: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<HelmChartListItem>> {
    return this.helmChartsApi
      .searchHelmCharts(this.repoName, query || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<HelmChartListItem>));
  }

  public getChart(name: string): Observable<HelmChartSummary> {
    return this.helmChartsApi.getHelmChart(this.repoName, name);
  }

  public fetchChartVersions(
    name: string,
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<HelmChartVersionItem>> {
    return this.helmChartsApi
      .listHelmChartVersions(this.repoName, name, search || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<HelmChartVersionItem>));
  }

  public getChartDetail(name: string, version: string): Observable<HelmChartDetail> {
    return this.helmChartsApi.getHelmChartDetail(this.repoName, name, version);
  }

  public deleteAllVersions(name: string): Observable<void> {
    return this.helmChartsApi.deleteAllHelmChartVersions(this.repoName, name).pipe(map(() => undefined));
  }

  public deleteChart(name: string, version: string): Observable<void> {
    return this.helmChartsApi.deleteHelmChartVersion(this.repoName, name, version).pipe(map(() => undefined));
  }

  public getOciTags(name: string): Observable<string[]> {
    return this.helmChartsApi.getHelmChartOciTags(this.repoName, name).pipe(map((r) => r ?? []));
  }
}
