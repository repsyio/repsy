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

import { HttpContext } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { map, tap } from 'rxjs/operators';

import {
  DockerImageControllerService,
  ImageListItem,
  ManifestListItem,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
  TagDetail,
} from '../../../../../../generated/api';
import { SILENT_ERROR } from '../../../../../shared/interceptor/error-handler.interceptor';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';
import { TagListItem } from '../dto/tag-list-item';

@Injectable({
  providedIn: 'root',
})
export class DockerService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly dockerImageControllerService: DockerImageControllerService,
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

  public searchImages(
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ImageListItem>> {
    return this.dockerImageControllerService
      .listDockerImages(this.repoName, name || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map((r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<ImageListItem>),
      );
  }

  public searchTags(
    name: string,
    sortOption: Sort,
    imageName: string,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<TagListItem>> {
    return this.dockerImageControllerService
      .listDockerImageTags(imageName, this.repoName, name || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<TagListItem>));
  }

  public searchManifests(
    name: string,
    sortOption: Sort,
    imageName: string,
    tagName: string,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ManifestListItem>> {
    return this.dockerImageControllerService
      .listDockerTagManifests(imageName, tagName, this.repoName, name || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(
        map((r) => ({ content: r.data?.content ?? [], page: r.data?.page }) as unknown as PagedData<ManifestListItem>),
      );
  }

  /**
   * The image as the list shows it. A 404 (the image went with its last manifest) is left to the
   * caller, which leaves the page instead of toasting an error.
   */
  public fetchImageSummary(imageName: string): Observable<ImageListItem> {
    return this.dockerImageControllerService
      .getDockerImageSummary(imageName, this.repoName, 'body', false, {
        context: new HttpContext().set(SILENT_ERROR, true),
      })
      .pipe(map((r) => r.data!));
  }

  public deleteImage(imageName: string): Observable<void> {
    return this.dockerImageControllerService.deleteDockerImage(imageName, this.repoName).pipe(map(() => undefined));
  }

  public fetchTag(imageName: string, tagName: string): Observable<TagDetail> {
    return this.dockerImageControllerService
      .getDockerImageTag(imageName, tagName, this.repoName)
      .pipe(map((r) => r.data!));
  }

  public deleteTag(imageName: string, tagName: string): Observable<void> {
    return this.dockerImageControllerService
      .deleteDockerTag(imageName, tagName, this.repoName)
      .pipe(map(() => undefined));
  }

  public fetchManifestText(imageName: string, digest: string): Observable<string> {
    return this.dockerImageControllerService
      .getDockerImageManifest(imageName, digest, this.repoName)
      .pipe(map((r) => r.data!));
  }

  public fetchConfigText(imageName: string, digest: string): Observable<string> {
    return this.dockerImageControllerService
      .getDockerImageConfig(imageName, digest, this.repoName)
      .pipe(map((r) => r.data!));
  }
}
