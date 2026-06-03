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
  HelmChartControllerService,
  HelmChartDetail,
  HelmChartListItem,
  HelmChartVersionItem,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
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
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly helmChartControllerService: HelmChartControllerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public getRepository(repoName: string): Observable<RepoPermissionInfo> {
    return this.protocolRepoControllerService.getPermission(repoName).pipe(
      map((r) => r.data!),
      tap((info) => this.repoSubject.next(info)),
    );
  }

  public searchCharts(
    query: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<HelmChartListItem>> {
    return this.helmChartControllerService
      .searchHelmCharts(
        { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
        this.repoName,
        query || undefined,
      )
      .pipe(
        map(
          (r) =>
            ({
              content: r.data?.content ?? [],
              page: r.data?.page,
            }) as unknown as PagedData<HelmChartListItem>,
        ),
      );
  }

  public getChartVersions(name: string): Observable<HelmChartVersionItem[]> {
    return this.helmChartControllerService
      .getHelmChartVersions(this.repoName, name)
      .pipe(map((r) => r.data ?? []));
  }

  public getChartDetail(name: string, version: string): Observable<HelmChartDetail> {
    return this.helmChartControllerService
      .getHelmChartDetail(this.repoName, name, version)
      .pipe(map((r) => r.data!));
  }

  public deleteChart(name: string, version: string): Observable<void> {
    return this.helmChartControllerService
      .deleteHelmChart(this.repoName, name, version)
      .pipe(map(() => undefined));
  }

  public getOciTags(name: string): Observable<string[]> {
    return this.helmChartControllerService
      .getHelmChartOciTags(this.repoName, name)
      .pipe(map((r) => r.data ?? []));
  }

}
