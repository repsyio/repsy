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
  GolangModuleControllerService,
  GoModuleInfo,
  GoModuleListItem,
  GoModuleVersionListItem,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';

@Injectable({
  providedIn: 'root',
})
export class GolangService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly golangModuleControllerService: GolangModuleControllerService,
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

  public fetchModules(sortOption: Sort, pageIndex: number, pageSize: number): Observable<PagedData<GoModuleListItem>> {
    return this.golangModuleControllerService
      .listGolangModules(
        { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
        this.repoName,
      )
      .pipe(
        map((r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<GoModuleListItem>),
      );
  }

  public searchModules(
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<GoModuleListItem>> {
    return this.golangModuleControllerService
      .searchGolangModules(
        { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
        this.repoName,
        search || undefined,
      )
      .pipe(
        map((r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<GoModuleListItem>),
      );
  }

  public deleteModule(modulePath: string): Observable<void> {
    return this.golangModuleControllerService.deleteGolangModule(modulePath, this.repoName).pipe(map(() => undefined));
  }

  public fetchModuleVersions(
    modulePath: string,
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<GoModuleVersionListItem>> {
    return this.golangModuleControllerService
      .listGolangModuleVersions(
        modulePath,
        { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
        this.repoName,
        search || undefined,
      )
      .pipe(
        map(
          (r) =>
            ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<GoModuleVersionListItem>,
        ),
      );
  }

  public fetchModuleInfo(modulePath: string): Observable<GoModuleInfo> {
    return this.golangModuleControllerService.getGolangModuleInfo(modulePath, this.repoName).pipe(map((r) => r.data!));
  }

  public deleteModuleVersion(modulePath: string, version: string): Observable<void> {
    return this.golangModuleControllerService
      .deleteGolangModuleVersion(modulePath, version, this.repoName)
      .pipe(map(() => undefined));
  }
}
