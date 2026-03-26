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

import { environment } from '../../../../../../environments/environment';
import { SpinnerComponent } from '../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../shared/components/dropdown/dropdown.component';
import { EmptyListComponent } from '../../../../shared/components/empty-list/empty-list.component';
import { SearchboxComponent } from '../../../../shared/components/searchbox/searchbox.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../../shared/dto/repo/repo-permission-info';
import { ByteFormatter } from '../../../../shared/util/byte-formatter';
import { MavenConfigComponent } from '../config/maven-config.component';
import { FsItemInfo } from '../dto/fs-item-info';
import { MavenService } from '../service/maven.service';

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
  public loading = false;
  public operationLock = false;
  public showConfig = false;
  public activeRepo: RepoPermissionInfo = new RepoPermissionInfo();
  public baseUrl: string;
  public repoUrl = '';
  public directoryStack: Directory[] = [];
  public forwardStack: Directory[] = [];
  public searchText: string;

  public fsItems: FsItemInfo[];
  public filteredFsItems: FsItemInfo[];

  private readonly repoChanges$: Subscription;

  constructor(
    private readonly mavenService: MavenService,
    private readonly toastService: ToastService,
  ) {
    this.repoChanges$ = this.mavenService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = repo;
        this.repoUrl = '';
        this.baseUrl = environment.apiBaseUrl;
        this.directoryStack = [];

        const rootItem = new FsItemInfo();
        rootItem.name = '/';
        rootItem.directory = true;

        this.go(rootItem);
      }
    });
  }

  public ngOnDestroy(): void {
    this.repoChanges$.unsubscribe();
  }

  public openConfig(open: boolean) {
    this.showConfig = open;
  }

  public search(fileName: string) {
    this.searchText = fileName;
    this.filteredFsItems = this.fsItems.filter((item) =>
      item.name.toLocaleLowerCase().includes(fileName.toLocaleLowerCase()),
    );
  }

  public prev(): void {
    if (this.directoryStack.length > 1) {
      const currentDir = this.directoryStack.pop();
      this.forwardStack.push(currentDir!);

      this.updateRepoUrl();
      this.fetchCurrentRepoContent();
    }
  }

  public next(): void {
    if (this.forwardStack.length > 0) {
      const nextDir = this.forwardStack.pop();
      this.directoryStack.push(nextDir!);

      this.updateRepoUrl();
      this.fetchCurrentRepoContent();
    }
  }

  public go(fsItem: FsItemInfo): void {
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
        const prev = this.directoryStack[this.directoryStack.length - 1];

        this.directoryStack.push(new Directory(requestedDirectoryName, prev.path + requestedDirectoryName, prev));
      }

      this.updateRepoUrl();
      this.fetchCurrentRepoContent();
    } else {
      const token = localStorage.getItem('token');
      const fullPath = this.directoryStack[this.directoryStack.length - 1].path + fsItem.name;

      location.href = `${environment.repoBaseUrl}/${this.activeRepo.repoName}` + `/${fullPath}?token=${token}`;
    }
  }

  public goToDir(dir: Directory): void {
    if (this.operationLock) {
      return;
    }

    // Reconstruct directory stack
    this.directoryStack = [];

    let currentDir = dir;

    while (currentDir !== null) {
      this.directoryStack.unshift(currentDir);
      currentDir = currentDir.prev;
    }

    this.updateRepoUrl();
    this.fetchCurrentRepoContent();
  }

  public goToBrowser(path: string) {
    location.href = `${environment.repoBaseUrl}//${this.activeRepo.repoName}${path}`;
  }

  public formatBytes(bytes: number, decimals = 2): string {
    return ByteFormatter.formatBytes(bytes, decimals);
  }

  private fetchCurrentRepoContent(): void {
    this.loading = true;
    this.operationLock = true;

    this.mavenService
      .getPathContent(this.directoryStack[this.directoryStack.length - 1].path)
      .then((items: FsItemInfo[]) => {
        this.fsItems = items;
        this.filteredFsItems = this.fsItems;
        this.operationLock = false;
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
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
