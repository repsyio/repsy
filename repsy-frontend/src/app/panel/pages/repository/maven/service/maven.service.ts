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
  MavenArtifactsApi,
  MavenGroupsApi,
  MavenGroupSummary,
  RepoPermissionInfo,
  ReposApi,
  RepoSettingsForm,
} from '../../../../../../generated/api';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';
import { isLastVersion, VERSION_PROBE_SIZE } from '../../../../shared/util/version-delete-landing.util';
import { FsItemInfo } from '../dto/fs-item-info';
import { lastVersionOfGroupWarning } from '../util/version-delete-warning.util';

/** The probe's sort: the versions of a Maven artifact sort by `versionName` (the shared probe sort is no column here). */
export const MAVEN_VERSION_PROBE_SORT: Sort = { name: 'Newest', column: 'versionName', type: 'DESC' };

@Injectable({
  providedIn: 'root',
})
export class MavenService {
  readonly repoChanges$: Observable<RepoPermissionInfo>;

  private readonly repoSubject$ = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly reposApi: ReposApi,
    private readonly mavenArtifactsApi: MavenArtifactsApi,
    private readonly mavenGroupsApi: MavenGroupsApi,
  ) {
    this.repoChanges$ = this.repoSubject$.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject$.getValue()?.repoName ?? '';
  }

  fetchRepoPermission(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.reposApi.getRepoPermissions(repoName).pipe(tap((info) => this.repoSubject$.next(info)));
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject$.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject$.next(null);
  }

  updateRepoSettings(form: RepoSettingsForm): Observable<void> {
    return this.reposApi.updateRepoSettings(this.repoName, form).pipe(map(() => undefined));
  }

  fetchPathContent(path: string): Observable<FsItemInfo[]> {
    return this.reposApi.getPathContent(path, this.repoName).pipe(map((r) => r as unknown as FsItemInfo[]));
  }

  createDownloadToken(path: string): Observable<string> {
    return this.reposApi.createDownloadToken(path, this.repoName);
  }

  searchGroups(
    groupName: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ArtifactListItem>> {
    return this.mavenArtifactsApi
      .listMavenGroups(this.repoName, groupName || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<ArtifactListItem>));
  }

  searchArtifacts(
    groupName: string,
    artifactName: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ArtifactListItem>> {
    return this.mavenArtifactsApi
      .listMavenArtifacts(groupName, this.repoName, artifactName || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<ArtifactListItem>));
  }

  searchArtifactVersions(
    groupName: string,
    artifactName: string,
    version: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ArtifactVersionListItem>> {
    return this.mavenArtifactsApi
      .listMavenArtifactVersions(groupName, artifactName, this.repoName, version || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<ArtifactVersionListItem>),
      );
  }

  fetchArtifactVersion(
    groupName: string,
    artifactName: string,
    versionName: string,
  ): Observable<ArtifactVersionInfo> {
    return this.mavenArtifactsApi.getMavenArtifactVersion(groupName, artifactName, versionName, this.repoName);
  }

  /** What deleting the group removes: how many artifacts and versions it holds. */
  fetchGroupSummary(groupName: string): Observable<MavenGroupSummary> {
    return this.mavenGroupsApi.getMavenGroupSummary(groupName, this.repoName);
  }

  /**
   * What the confirmation of deleting a version has to add (RPS-1348): the warning when it is the artifact's
   * last version and the artifact is the only one of its group, as the server then removes both with it;
   * `null` when only the version goes. Read from the versions probe and the group summary; a probe that
   * fails asks for nothing more than the plain confirmation.
   */
  fetchVersionDeleteWarning(groupName: string, artifactName: string): Observable<string | null> {
    return this.searchArtifactVersions(
      groupName,
      artifactName,
      '',
      MAVEN_VERSION_PROBE_SORT,
      0,
      VERSION_PROBE_SIZE,
    ).pipe(
      switchMap((probe) => (isLastVersion(probe) ? this.fetchGroupSummary(groupName) : of(null))),
      map((summary) =>
        summary && summary.artifactCount === 1 ? lastVersionOfGroupWarning(groupName, artifactName) : null,
      ),
      catchError(() => of(null)),
    );
  }

  deleteGroup(groupName: string): Observable<void> {
    return this.mavenArtifactsApi.deleteMavenGroup(groupName, this.repoName);
  }

  deleteArtifact(groupName: string, artifactName: string): Observable<void> {
    return this.mavenArtifactsApi.deleteMavenArtifact(groupName, artifactName, this.repoName);
  }

  deleteVersion(groupName: string, artifactName: string, versionName: string): Observable<void> {
    return this.mavenArtifactsApi.deleteMavenArtifactVersion(groupName, artifactName, versionName, this.repoName);
  }
}
