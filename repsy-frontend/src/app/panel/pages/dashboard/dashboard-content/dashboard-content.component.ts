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
import { ChangeDetectorRef, Component } from '@angular/core';
import { RouterModule } from '@angular/router';
import { catchError, forkJoin, map, of, switchMap } from 'rxjs';

import { RepoListInfo, RepoType, TotalUsageInfo } from '../../../../../generated/api';
import { ProtocolRepoControllerService } from '../../../../../generated/api';
import { SILENT_ERROR } from '../../../../shared/interceptor/error-handler.interceptor';
import { RepositoryCreateModalComponent } from '../../../shared/components/modals/repository-create-modal/repository-create-modal.component';
import { ProfileService } from '../../profile/service/profile.service';
import { RecentActivityComponent } from '../recent-activity/recent-activity.component';
import { RepositoryCardComponent } from '../repository-card/repository-card.component';
import { SecurityOverviewCardComponent } from '../security-overview-card/security-overview-card.component';
import { UsageService } from '../service/usage.service';
import { TotalDiskComponent } from '../total-disk/total-disk.component';
import { WelcomeCardComponent } from '../welcome-card/welcome-card.component';

// The usage of a repository needs MANAGE, so a USER gets a 403 for every repository and the card shows
// an unknown disk usage instead: nine "Access denied" toasts on every visit would only be noise.
const SILENT_USAGE = new HttpContext().set(SILENT_ERROR, true);

interface Repository {
  type: RepoType;
  usageCount: number;
}

@Component({
  selector: 'app-dashboard-content',
  imports: [
    WelcomeCardComponent,
    TotalDiskComponent,
    RecentActivityComponent,
    RepositoryCardComponent,
    SecurityOverviewCardComponent,
    RouterModule,
    RepositoryCreateModalComponent,
  ],
  templateUrl: './dashboard-content.component.html',
})
export class DashboardContentComponent {
  public username = '';
  public usage: TotalUsageInfo = {} as TotalUsageInfo;
  public mavenRepoCount = 0;
  public npmRegistryCount = 0;
  public pypiRepoCount = 0;
  public dockerRepoCount = 0;
  public cargoRepoCount = 0;
  public golangRepoCount = 0;
  public helmRepoCount = 0;
  public nugetRepoCount = 0;
  public rubyRepoCount = 0;
  public repositories: Repository[] = [];
  public repoListInfos: RepoListInfo[] = [];
  public createRepoModal: boolean;
  public isAdmin = false;

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly usageService: UsageService,
    private readonly profileService: ProfileService,
    private readonly cdRef: ChangeDetectorRef,
  ) {
    // The HTTP error interceptor already shows a failed request. Every subscription here still
    // needs its own error handler, so that one failing source leaves that part of the dashboard
    // empty instead of becoming an unhandled RxJS error.
    this.usageService.getTotalUsage().subscribe({
      next: (usage) => {
        Object.assign(this.usage, usage);
      },
      error: () => {},
    });

    this.profileService.get().subscribe({
      next: (profile) => {
        this.isAdmin = profile.role === 'ADMIN';
        if (this.isAdmin) {
          this.fetchRepoCounts();
        }
        this.cdRef.markForCheck();
      },
      error: () => {},
    });
    this.fetchRepoInfos();
  }

  public openCreateRepo(): void {
    if (this.isAdmin) {
      this.createRepoModal = true;
    }
  }

  private fetchRepoCounts(): void {
    this.fetchRepoCount(RepoType.Npm, (c) => (this.npmRegistryCount = c));
    this.fetchRepoCount(RepoType.Pypi, (c) => (this.pypiRepoCount = c));
    this.fetchRepoCount(RepoType.Maven, (c) => (this.mavenRepoCount = c));
    this.fetchRepoCount(RepoType.Docker, (c) => (this.dockerRepoCount = c));
    this.fetchRepoCount(RepoType.Cargo, (c) => (this.cargoRepoCount = c));
    this.fetchRepoCount(RepoType.Golang, (c) => (this.golangRepoCount = c));
    this.fetchRepoCount(RepoType.Helm, (c) => (this.helmRepoCount = c));
    this.fetchRepoCount(RepoType.Nuget, (c) => (this.nugetRepoCount = c));
    this.fetchRepoCount(RepoType.Ruby, (c) => (this.rubyRepoCount = c));
  }

  private fetchRepoCount(repoType: RepoType, assign: (count: number) => void): void {
    this.protocolRepoControllerService
      .getCount(repoType)
      .pipe(map((r) => r.data ?? 0))
      .subscribe({
        next: (count) => {
          assign(count);
          this.cdRef.markForCheck();
        },
        // A count that cannot be fetched stays at zero.
        error: () => {},
      });
  }

  private fetchRepoInfo(repoType: RepoType): void {
    this.protocolRepoControllerService
      .getInfo(repoType)
      .pipe(
        map((r) => r.data ?? []),
        switchMap((repos) => {
          if (repos.length === 0) {
            return of([]);
          }
          return forkJoin(
            repos.map((repo) =>
              this.protocolRepoControllerService.getUsage(repo.name, 'body', false, { context: SILENT_USAGE }).pipe(
                // A repository whose usage cannot be fetched still shows, with an unknown disk usage,
                // instead of failing the forkJoin and dropping every repository of its type.
                map((r) => r.data?.diskUsed?.value),
                catchError(() => of(undefined)),
                map((diskUsage) => {
                  repo.diskUsage = diskUsage;
                  repo.type = repoType;
                  return repo;
                }),
              ),
            ),
          );
        }),
      )
      .subscribe({
        next: (updatedRepos) => {
          this.repoListInfos = this.repoListInfos
            .concat(updatedRepos)
            .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
            .slice(0, 6);
          this.cdRef.markForCheck();
        },
        // The HTTP error interceptor already shows the failure; a type that cannot be listed just
        // contributes no recent repositories, and must not become an unhandled RxJS error.
        error: () => {},
      });
  }

  private fetchRepoInfos(): void {
    this.fetchRepoInfo(RepoType.Maven);
    this.fetchRepoInfo(RepoType.Npm);
    this.fetchRepoInfo(RepoType.Pypi);
    this.fetchRepoInfo(RepoType.Docker);
    this.fetchRepoInfo(RepoType.Cargo);
    this.fetchRepoInfo(RepoType.Golang);
    this.fetchRepoInfo(RepoType.Helm);
    this.fetchRepoInfo(RepoType.Nuget);
    this.fetchRepoInfo(RepoType.Ruby);
  }
}
