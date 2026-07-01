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
  GemListItem,
  GemVersionInfo,
  GemVersionListItem,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
  RubyGemApiControllerService,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';

@Injectable({
  providedIn: 'root',
})
export class RubyService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly rubyGemApiControllerService: RubyGemApiControllerService,
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

  public searchGems(
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<GemListItem>> {
    return this.rubyGemApiControllerService
      .listGems(
        { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
        this.repoName,
        search || undefined,
      )
      .pipe(
        map((r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<GemListItem>),
      );
  }

  public fetchGemVersions(
    gemName: string,
    search: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<GemVersionListItem>> {
    return this.rubyGemApiControllerService
      .listGemVersions(
        gemName,
        { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
        this.repoName,
        search || undefined,
      )
      .pipe(
        map(
          (r) =>
            ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<GemVersionListItem>,
        ),
      );
  }

  public fetchGemVersion(gemName: string, version: string, platform?: string): Observable<GemVersionInfo> {
    return this.rubyGemApiControllerService
      .getGemVersion(gemName, version, this.repoName, platform)
      .pipe(map((r) => r.data as GemVersionInfo));
  }

  public deleteGem(gemName: string): Observable<void> {
    return this.rubyGemApiControllerService.deleteGem(gemName, this.repoName).pipe(map(() => undefined));
  }

  public deleteGemVersion(gemName: string, version: string, platform: string): Observable<void> {
    return this.rubyGemApiControllerService
      .deleteGemVersion(gemName, version, this.repoName, platform)
      .pipe(map(() => undefined));
  }
}
