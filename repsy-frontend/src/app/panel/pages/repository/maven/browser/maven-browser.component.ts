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

import { CommonModule, NgOptimizedImage } from '@angular/common';
import { Component, OnDestroy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../environments/environment';
import { RepoPermissionInfo } from '../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../shared/components/dropdown/dropdown.component';
import { EmptyListComponent } from '../../../../shared/components/empty-list/empty-list.component';
import { SearchboxComponent } from '../../../../shared/components/searchbox/searchbox.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ByteFormatter } from '../../../../shared/utils/byte-formatter';
import { MavenConfigComponent } from '../config/maven-config.component';
import { FsItemInfo } from '../dtos/fs-item-info';
import { MavenService } from '../services/maven.service';

class Directory {
  constructor(
    readonly name: string,
    readonly path: string,
    readonly prev: Directory,
  ) {}
}

@Component({
  selector: 'app-maven-browser',
  standalone: true,
  imports: [
    CommonModule,
    DropdownComponent,
    RouterLink,
    MavenConfigComponent,
    SearchboxComponent,
    EmptyListComponent,
    NgOptimizedImage,
    SpinnerComponent,
  ],
  templateUrl: './maven-browser.component.html',
})
export class MavenBrowserComponent implements OnDestroy {
  loading = false;
  operationLock = false;
  showConfig = false;
  activeRepo: RepoPermissionInfo = {} as RepoPermissionInfo;
  baseUrl: string;
  repoUrl = '';
  directoryStack: Directory[] = [];
  forwardStack: Directory[] = [];
  searchText = '';

  fsItems: FsItemInfo[];
  filteredFsItems: FsItemInfo[];

  private readonly repoChanges$: Subscription;

  constructor(
    private readonly mavenService: MavenService,
    private readonly toastService: ToastService,
  ) {
    this.repoChanges$ = this.mavenService.repoChanges$.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        // A reload of the permissions of the repository that is already open must not send the user
        // back to the root, or reset the path while its first listing is still in flight.
        const sameRepo = this.activeRepo.repoName === repo.repoName && this.directoryStack.length > 0;
        this.activeRepo = repo;
        if (sameRepo) {
          return;
        }

        this.repoUrl = '';
        this.baseUrl = environment.apiBaseUrl;
        this.directoryStack = [];
        this.forwardStack = [];

        const rootItem = new FsItemInfo();
        rootItem.name = '/';
        rootItem.directory = true;

        this.go(rootItem);
      }
    });
  }

  ngOnDestroy(): void {
    this.repoChanges$.unsubscribe();
  }

  openConfig(open: boolean) {
    this.showConfig = open;
  }

  search(fileName: string) {
    this.searchText = fileName;
    // No listing (the directory could not be read): there is nothing to filter, and the not-found state stays.
    if (!this.fsItems) {
      return;
    }

    this.filteredFsItems = this.fsItems.filter((item) =>
      item.name.toLocaleLowerCase().includes(fileName.toLocaleLowerCase()),
    );
  }

  prev(): void {
    if (this.directoryStack.length > 1) {
      const currentDir = this.directoryStack.pop();
      this.forwardStack.push(currentDir!);

      this.updateRepoUrl();
      this.fetchCurrentRepoContent();
    }
  }

  next(): void {
    if (this.forwardStack.length > 0) {
      const nextDir = this.forwardStack.pop();
      this.directoryStack.push(nextDir!);

      this.updateRepoUrl();
      this.fetchCurrentRepoContent();
    }
  }

  go(fsItem: FsItemInfo): void {
    if (this.operationLock) {
      return;
    }

    if (fsItem.directory) {
      const requestedDirectoryName = fsItem.name;

      if (requestedDirectoryName === '../') {
        const currentDir = this.directoryStack.pop();
        this.forwardStack.push(currentDir!);
      } else if (this.directoryStack.length === 0) {
        this.directoryStack.push(new Directory('/', '/', null));
      } else {
        // A new forward navigation invalidates whatever "next" would have gone back to.
        this.forwardStack = [];
        const prev = this.directoryStack[this.directoryStack.length - 1];

        this.directoryStack.push(new Directory(requestedDirectoryName, prev.path + requestedDirectoryName, prev));
      }

      this.updateRepoUrl();
      this.fetchCurrentRepoContent();
    } else {
      this.download(this.directoryStack[this.directoryStack.length - 1].path + fsItem.name);
    }
  }

  goToDir(dir: Directory): void {
    if (this.operationLock) {
      return;
    }

    // Reconstruct directory stack
    this.directoryStack = [];
    this.forwardStack = [];

    let currentDir = dir;

    while (currentDir !== null) {
      this.directoryStack.unshift(currentDir);
      currentDir = currentDir.prev;
    }

    this.updateRepoUrl();
    this.fetchCurrentRepoContent();
  }

  goToBrowser(path: string) {
    location.href = `${environment.repoBaseUrl}//${this.activeRepo.repoName}${path}`;
  }

  formatBytes(bytes: number, decimals = 2): string {
    return ByteFormatter.formatBytes(bytes, decimals);
  }

  // A navigation cannot set an Authorization header, so the file is requested with a token that
  // opens only this path for a minute, instead of the session's access token.
  private download(fullPath: string): void {
    this.mavenService.createDownloadToken(fullPath).subscribe({
      next: (downloadToken) => {
        location.href = `${environment.repoBaseUrl}/${this.activeRepo.repoName}${fullPath}?downloadToken=${encodeURIComponent(downloadToken)}`;
      },
      // The HTTP error interceptor already shows the failure; nothing is downloaded, and the
      // failure must not become an unhandled RxJS error.
      error: () => {},
    });
  }

  private fetchCurrentRepoContent(): void {
    this.loading = true;
    this.operationLock = true;
    // The file name filter belongs to the directory it was typed in: the box is emptied with the listing.
    this.searchText = '';

    this.mavenService
      .fetchPathContent(this.directoryStack[this.directoryStack.length - 1].path)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (items: FsItemInfo[]) => {
          this.fsItems = items;
          this.filteredFsItems = this.fsItems;
          this.operationLock = false;
        },
        error: () => {
          // The directory asked for cannot be listed (it was deleted meanwhile, or the request failed; the
          // HTTP error interceptor says why). Show that, under the path that was asked for, instead of the
          // previous directory's files under a path they are not in. The breadcrumb and Back still work.
          this.fsItems = undefined;
          this.filteredFsItems = undefined;
          this.operationLock = false;
        },
      });
  }

  private updateRepoUrl(): void {
    this.repoUrl =
      environment.repoBaseUrl +
      '/' +
      this.activeRepo.repoName +
      this.directoryStack[this.directoryStack.length - 1].path;
  }
}
