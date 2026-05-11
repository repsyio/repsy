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
import { DeletedItem } from '../dto/deleted-item';
import { FsItemInfo } from '../dto/fs-item-info';
import {
  ArtifactListItem,
  ArtifactVersionInfo,
  ArtifactVersionListItem,
  MavenArtifactControllerService,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
  RepoSettingsForm,
} from '../../../../../../generated/api';

@Injectable({
  providedIn: 'root',
})
export class MavenService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly mavenArtifactControllerService: MavenArtifactControllerService,
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

  public updateRepoSettings(form: RepoSettingsForm): Observable<void> {
    return this.protocolRepoControllerService.updateSettings(this.repoName, form).pipe(map(() => undefined));
  }

  public getPathContent(path: string): Observable<FsItemInfo[]> {
    return this.protocolRepoControllerService.getPathContent(path, this.repoName).pipe(
      map(r => r.data as unknown as FsItemInfo[]),
    );
  }

  public searchGroups(groupName: string, sortOption: Sort, pageIndex: number, pageSize: number): Observable<PagedData<ArtifactListItem>> {
    return this.mavenArtifactControllerService.listContainsGroupName(
      { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
      this.repoName,
      groupName || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<ArtifactListItem>)),
    );
  }

  public searchArtifacts(
    groupName: string,
    artifactName: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ArtifactListItem>> {
    return this.mavenArtifactControllerService.listContainsArtifactName(
      groupName,
      { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
      this.repoName,
      artifactName || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<ArtifactListItem>)),
    );
  }

  public searchArtifactVersions(
    groupName: string,
    artifactName: string,
    version: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ArtifactVersionListItem>> {
    return this.mavenArtifactControllerService.listMavenArtifactVersions(
      groupName,
      artifactName,
      { page: pageIndex, size: pageSize, sort: [`${sortOption.column},${sortOption.type}`] },
      this.repoName,
      version || undefined,
    ).pipe(
      map(r => ({ content: r.data?.content ?? [], page: r.data?.page } as unknown as PagedData<ArtifactVersionListItem>)),
    );
  }

  public fetchArtifactVersion(groupName: string, artifactName: string, versionName: string): Observable<ArtifactVersionInfo> {
    return this.mavenArtifactControllerService.getMavenArtifactVersion(groupName, artifactName, versionName, this.repoName).pipe(
      map(r => r.data!),
    );
  }

  public deleteGroup(groupName: string): Observable<DeletedItem> {
    return this.mavenArtifactControllerService.deleteGroup(groupName, this.repoName).pipe(
      map(r => r.data as unknown as DeletedItem),
    );
  }

  public deleteArtifact(groupName: string, artifactName: string): Observable<DeletedItem> {
    return this.mavenArtifactControllerService.deleteMavenArtifact(groupName, artifactName, this.repoName).pipe(
      map(r => r.data as unknown as DeletedItem),
    );
  }

  public deleteVersion(groupName: string, artifactName: string, versionName: string): Observable<DeletedItem> {
    return this.mavenArtifactControllerService.deleteMavenArtifactVersion(groupName, artifactName, versionName, this.repoName).pipe(
      map(r => r.data as unknown as DeletedItem),
    );
  }
}
