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

import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';
import { CrateInfo } from '../dto/crate-info';
import { CrateVersionInfo } from '../dto/crate-version-info';
import {
  CargoCrateControllerService,
  CrateListItem,
  CrateVersionListItem,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
} from '../../../../../../generated/api';

@Injectable({
  providedIn: 'root',
})
export class CargoService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly cargoCrateControllerService: CargoCrateControllerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public getRepository(repoName: string): Observable<RepoPermissionInfo> {
    return this.protocolRepoControllerService.getPermission(repoName).pipe(
      map(r => r.data!),
      tap(info => this.repoSubject.next(info)),
    );
  }

  public searchCrates(search: string, sortOption: Sort, pageIndex: number, pageSize: number): Observable<PagedData<CrateListItem>> {
    return this.cargoCrateControllerService.searchCargoCrates(
      { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
      this.repoName,
      search || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<CrateListItem>)),
    );
  }

  public fetchCrate(crateName: string): Observable<CrateInfo> {
    return this.cargoCrateControllerService.getCargoCrate(crateName, this.repoName).pipe(
      map(r => r.data as unknown as CrateInfo),
    );
  }

  public fetchCrateVersion(crateName: string, version: string): Observable<CrateVersionInfo> {
    return this.cargoCrateControllerService.getCargoCrateVersion(crateName, version, this.repoName).pipe(
      map(r => r.data as unknown as CrateVersionInfo),
    );
  }

  public fetchCrateVersions(
    crateName: string,
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<CrateVersionListItem>> {
    return this.cargoCrateControllerService.listCargoCrateVersions(
      crateName,
      { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
      this.repoName,
      search || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<CrateVersionListItem>)),
    );
  }

  public deleteCrate(crateName: string): Observable<void> {
    return this.cargoCrateControllerService.deleteCargoCrate(crateName, this.repoName).pipe(map(() => undefined));
  }

  public deleteCrateVersion(crateName: string, version: string): Observable<void> {
    return this.cargoCrateControllerService.deleteCargoCrateVersion(crateName, version, this.repoName).pipe(
      map(() => undefined),
    );
  }
}
