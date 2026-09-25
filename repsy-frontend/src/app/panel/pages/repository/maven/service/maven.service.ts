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
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, map, switchMap, tap } from 'rxjs/operators';

import {
  ArtifactListItem,
  ArtifactVersionInfo,
  ArtifactVersionListItem,
  MavenArtifactControllerService,
  MavenGroupControllerService,
  MavenGroupSummary,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
  RepoSettingsForm,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';
import {
  isLastVersion,
  VERSION_PROBE_SIZE,
  VERSION_PROBE_SORT,
} from '../../../../shared/util/version-delete-landing.util';
import { DeletedItem } from '../dto/deleted-item';
import { FsItemInfo } from '../dto/fs-item-info';
import { lastVersionOfGroupWarning } from '../util/version-delete-warning.util';

@Injectable({
  providedIn: 'root',
})
export class MavenService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly mavenArtifactControllerService: MavenArtifactControllerService,
    private readonly mavenGroupControllerService: MavenGroupControllerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  public getRepository(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.protocolRepoControllerService.getRepoPermissions(repoName).pipe(
      map((r) => r.data!),
      tap((info) => this.repoSubject.next(info)),
    );
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject.next(null);
  }

  public updateRepoSettings(form: RepoSettingsForm): Observable<void> {
    return this.protocolRepoControllerService.updateRepoSettings(this.repoName, form).pipe(map(() => undefined));
  }

  public getPathContent(path: string): Observable<FsItemInfo[]> {
    return this.protocolRepoControllerService
      .getPathContent(path, this.repoName)
      .pipe(map((r) => r.data as unknown as FsItemInfo[]));
  }

  public createDownloadToken(path: string): Observable<string> {
    return this.protocolRepoControllerService.createDownloadToken(path, this.repoName).pipe(map((r) => r.data!));
  }

  public searchGroups(
    groupName: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ArtifactListItem>> {
    return this.mavenArtifactControllerService
      .listMavenGroups(this.repoName, groupName || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map((r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<ArtifactListItem>),
      );
  }

  public searchArtifacts(
    groupName: string,
    artifactName: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ArtifactListItem>> {
    return this.mavenArtifactControllerService
      .listMavenArtifacts(groupName, this.repoName, artifactName || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map((r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<ArtifactListItem>),
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
    return this.mavenArtifactControllerService
      .listMavenArtifactVersions(groupName, artifactName, this.repoName, version || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map(
          (r) =>
            ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<ArtifactVersionListItem>,
        ),
      );
  }

  public fetchArtifactVersion(
    groupName: string,
    artifactName: string,
    versionName: string,
  ): Observable<ArtifactVersionInfo> {
    return this.mavenArtifactControllerService
      .getMavenArtifactVersion(groupName, artifactName, versionName, this.repoName)
      .pipe(map((r) => r.data!));
  }

  /** What deleting the group removes: how many artifacts and versions it holds. */
  public getGroupSummary(groupName: string): Observable<MavenGroupSummary> {
    return this.mavenGroupControllerService.getMavenGroupSummary(groupName, this.repoName).pipe(map((r) => r.data!));
  }

  /**
   * What the confirmation of deleting a version has to add (RPS-1348): the warning when it is the artifact's
   * last version and the artifact is the only one of its group, as the server then removes both with it;
   * `null` when only the version goes. Read from the versions probe and the group summary; a probe that
   * fails asks for nothing more than the plain confirmation.
   */
  public getVersionDeleteWarning(groupName: string, artifactName: string): Observable<string | null> {
    return this.searchArtifactVersions(groupName, artifactName, '', VERSION_PROBE_SORT, 0, VERSION_PROBE_SIZE).pipe(
      switchMap((probe) => (isLastVersion(probe) ? this.getGroupSummary(groupName) : of(null))),
      map((summary) =>
        summary && summary.artifactCount === 1 ? lastVersionOfGroupWarning(groupName, artifactName) : null,
      ),
      catchError(() => of(null)),
    );
  }

  public deleteGroup(groupName: string): Observable<DeletedItem> {
    return this.mavenArtifactControllerService
      .deleteGroup(groupName, this.repoName)
      .pipe(map((r) => r.data as unknown as DeletedItem));
  }

  public deleteArtifact(groupName: string, artifactName: string): Observable<DeletedItem> {
    return this.mavenArtifactControllerService
      .deleteMavenArtifact(groupName, artifactName, this.repoName)
      .pipe(map((r) => r.data as unknown as DeletedItem));
  }

  public deleteVersion(groupName: string, artifactName: string, versionName: string): Observable<DeletedItem> {
    return this.mavenArtifactControllerService
      .deleteMavenArtifactVersion(groupName, artifactName, versionName, this.repoName)
      .pipe(map((r) => r.data as unknown as DeletedItem));
  }
}
