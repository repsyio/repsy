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
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import moment from 'moment';

import { SpinnerComponent } from '../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { EllipsisPipe } from '../../shared/components/ellipsis/ellipsis.pipe';
import { EmptyListComponent } from '../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../shared/components/modals/danger-modal/danger-modal.service';
import { RepositoryCreateModalComponent } from '../../shared/components/modals/repository-create-modal/repository-create-modal.component';
import { PaginationComponent } from '../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../shared/components/searchbox/searchbox.component';
import { SelectorComponent } from '../../shared/components/selector/selector.component';
import { ToastService } from '../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../shared/components/tooltip/tooltip.component';
import { RepoListItem } from '../../shared/dto/repo/repo-list-item';
import { RepoType } from '../../shared/dto/repo/repo-type';
import { ByteFormatter } from '../../shared/util/byte-formatter';
import { ProfileService } from '../profile/service/profile.service';
import { DockerService } from './docker/service/docker.service';
import { GolangService } from './golang/service/golang.service';
import { MavenService } from './maven/service/maven.service';
import { NpmService } from './npm/service/npm.service';
import { PypiService } from './pypi/service/pypi.service';

@Component({
  selector: 'app-repository',
  standalone: true,
  imports: [
    CommonModule,
    NgOptimizedImage,
    EmptyListComponent,
    SearchboxComponent,
    SelectorComponent,
    RouterLink,
    DropdownComponent,
    PaginationComponent,
    RepositoryCreateModalComponent,
    TooltipComponent,
    EllipsisPipe,
    SpinnerComponent,
  ],
  templateUrl: './repository.component.html',
})
export class RepositoryComponent {
  public pageNum = 0;
  public pageSize = 10;
  public repositories: RepoListItem[] = [];
  public filteredRepos: RepoListItem[] = [];
  public paginatedRepos: RepoListItem[] = [];
  public createRepoModal: boolean;
  public repoOption = RepoType.ALL;
  public repoOptions = [RepoType.ALL, RepoType.DOCKER, RepoType.GOLANG, RepoType.MAVEN, RepoType.NPM, RepoType.PYPI];
  public loading = true;
  public operationLock = false;
  public username: string;
  public error: string;
  public isAdmin = false;

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly golangService: GolangService,
    private readonly profileService: ProfileService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    const state = window.history.state;

    if (state && state.repoType) {
      this.repoOption = state.repoType as RepoType;
    }

    this.loadUserRole();
    this.filterRepos(this.repoOption);
  }

  public loadPage(pageNum: number): void {
    const startIndex = pageNum * this.pageSize;
    const endIndex = startIndex + this.pageSize;

    this.paginatedRepos = this.filteredRepos
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(startIndex, endIndex);
  }

  public search(repoName: string) {
    this.filteredRepos = this.repositories.filter((repo) => repo.name.toLowerCase().includes(repoName.toLowerCase()));

    this.loadPage(0);
  }

  public refreshPage(): void {
    this.filterRepos(this.repoOption);
  }

  public getTotalPages(): number {
    return Math.ceil(this.filteredRepos.length / this.pageSize);
  }

  public formatBytes(bytes: number, decimals = 2): string {
    return ByteFormatter.formatBytes(bytes, decimals);
  }

  public filterRepos(option: string) {
    this.loading = true;
    this.repositories = [];
    this.filteredRepos = [];

    this.loadAllRepos(option);
  }

  public deleteRepository(repo: RepoListItem) {
    if (!this.isAdmin) {
      this.toastService.show('You do not have permission to delete repositories', 'error');
      return;
    }
    this.dangerModalService.show('Delete Repository', 'Delete', () => {
      this.operationLock = true;
      this.deleteRepositoryService(repo)
        .then(() => {
          this.refreshPage();
          this.toastService.show('Repository deleted successfully', 'success');
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.operationLock = false;
        });
    });
  }

  public openCreateRepoModal() {
    this.createRepoModal = true;
  }

  public timeAgo(date: Date | string): string {
    return moment(date).fromNow();
  }

  private fetchAllRepositories(): void {
    this.fetchRepositories(RepoType.MAVEN);
    this.fetchRepositories(RepoType.NPM);
    this.fetchRepositories(RepoType.PYPI);
    this.fetchRepositories(RepoType.DOCKER);
    this.fetchRepositories(RepoType.GOLANG);
  }

  private fetchRepositories(repoType: RepoType): void {
    this.loading = true;
    this.fetchRepositoriesService(repoType)
      .then((repos: RepoListItem[]) => {
        const temp = repos.map((repo: RepoListItem) => {
          repo.repoType = repoType;
          return repo;
        });

        this.repositories.push(...temp);
        this.filteredRepos.push(...temp);
        this.loadPage(0);
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  private fetchRepositoriesService(repoType: RepoType): Promise<RepoListItem[]> {
    switch (repoType) {
      case RepoType.MAVEN:
        return this.mavenService.fetchRepos();
      case RepoType.NPM:
        return this.npmService.fetchRegistries();
      case RepoType.PYPI:
        return this.pypiService.fetchRepositories();
      case RepoType.DOCKER:
        return this.dockerService.fetchRepositories();
      case RepoType.GOLANG:
        return this.golangService.fetchRepositories();
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  private deleteRepositoryService(repo: RepoListItem) {
    switch (repo.repoType) {
      case RepoType.MAVEN:
        return this.mavenService.deleteRepository(repo.name);
      case RepoType.NPM:
        return this.npmService.deleteRegistry(repo.name);
      case RepoType.PYPI:
        return this.pypiService.deleteRepository(repo.name);
      case RepoType.DOCKER:
        return this.dockerService.deleteRepository(repo.name);
      case RepoType.GOLANG:
        return this.golangService.deleteRepository(repo.name);
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  private loadAllRepos(option: string) {
    switch (option) {
      case RepoType.ALL:
        this.fetchAllRepositories();
        break;
      default:
        this.fetchRepositories(option as RepoType);
        break;
    }
  }

  private loadUserRole(): void {
    this.profileService.get().then((profile) => {
      this.isAdmin = profile.role === 'ADMIN';
    });
  }
}
