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
  DockerImagesApi,
  ImageListItem,
  ManifestListItem,
  RepoPermissionInfo,
  ReposApi,
  TagDetail,
} from '../../../../../../generated/api';
import { SILENT_ERROR } from '../../../../../shared/interceptor/error-handler.interceptor';
import { PagedData } from '../../../../shared/dto/paged-data';
import { Sort } from '../../../../shared/dto/sort';
import { TagListItem } from '../dto/tag-list-item';

/**
 * A Docker image name can have several segments (`team/app`), which one path segment cannot carry
 * and the server does not accept encoded slashes in. Such a name is sent in the `image` query and
 * `-`, which is not a valid image name, stands in the path.
 */
const pathName = (imageName: string): string => (imageName.includes('/') ? '-' : imageName);
const queryName = (imageName: string): string | undefined => (imageName.includes('/') ? imageName : undefined);

@Injectable({
  providedIn: 'root',
})
export class DockerService {
  readonly repoChanges: Observable<RepoPermissionInfo>;

  private readonly repoSubject = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly reposApi: ReposApi,
    private readonly dockerImagesApi: DockerImagesApi,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  private get repoName(): string {
    return this.repoSubject.getValue()?.repoName ?? '';
  }

  fetchRepoPermission(repoName: string): Observable<RepoPermissionInfo> {
    this.resetActiveRepoIfChanged(repoName);

    return this.reposApi.getRepoPermissions(repoName).pipe(tap((info) => this.repoSubject.next(info)));
  }

  private resetActiveRepoIfChanged(repoName: string): void {
    if (this.repoSubject.getValue()?.repoName === repoName) {
      return;
    }

    this.repoSubject.next(null);
  }

  searchImages(
    name: string,
    sortOption: Sort,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ImageListItem>> {
    return this.dockerImagesApi
      .listDockerImages(this.repoName, name || undefined, pageIndex, pageSize, [
        `${sortOption.column},${sortOption.type}`,
      ])
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<ImageListItem>));
  }

  searchTags(
    name: string,
    sortOption: Sort,
    imageName: string,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<TagListItem>> {
    return this.dockerImagesApi
      .listDockerImageTags(
        pathName(imageName),
        this.repoName,
        queryName(imageName),
        name || undefined,
        pageIndex,
        pageSize,
        [`${sortOption.column},${sortOption.type}`],
      )
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<TagListItem>));
  }

  searchManifests(
    name: string,
    sortOption: Sort,
    imageName: string,
    tagName: string,
    pageIndex: number,
    pageSize: number,
  ): Observable<PagedData<ManifestListItem>> {
    return this.dockerImagesApi
      .listDockerTagManifests(
        pathName(imageName),
        tagName,
        this.repoName,
        queryName(imageName),
        name || undefined,
        pageIndex,
        pageSize,
        [`${sortOption.column},${sortOption.type}`],
      )
      .pipe(map((r) => ({ content: r?.content ?? [], page: r?.page }) as unknown as PagedData<ManifestListItem>));
  }

  /**
   * The image as the list shows it. A 404 (the image went with its last manifest) is left to the
   * caller, which leaves the page instead of toasting an error.
   */
  fetchImageSummary(imageName: string): Observable<ImageListItem> {
    return this.dockerImagesApi.getDockerImage(
      pathName(imageName),
      this.repoName,
      queryName(imageName),
      'body',
      false,
      {
        context: new HttpContext().set(SILENT_ERROR, true),
      },
    );
  }

  deleteImage(imageName: string): Observable<void> {
    return this.dockerImagesApi
      .deleteDockerImage(pathName(imageName), this.repoName, queryName(imageName))
      .pipe(map(() => undefined));
  }

  fetchTag(imageName: string, tagName: string): Observable<TagDetail> {
    return this.dockerImagesApi.getDockerImageTag(pathName(imageName), tagName, this.repoName, queryName(imageName));
  }

  deleteTag(imageName: string, tagName: string): Observable<void> {
    return this.dockerImagesApi
      .deleteDockerTag(pathName(imageName), tagName, this.repoName, queryName(imageName))
      .pipe(map(() => undefined));
  }

  fetchManifestText(imageName: string, digest: string): Observable<string> {
    return this.dockerImagesApi.getDockerImageManifest(
      pathName(imageName),
      digest,
      this.repoName,
      queryName(imageName),
    );
  }

  fetchConfigText(imageName: string, digest: string): Observable<string> {
    return this.dockerImagesApi.getDockerImageConfig(pathName(imageName), digest, this.repoName, queryName(imageName));
  }
}
