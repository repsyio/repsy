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

import { ChangeDetectorRef, Component } from '@angular/core';
import { RouterModule } from '@angular/router';

import { RepoCollectionControllerService, RepoListInfo, RepoType, TotalUsageInfo } from '../../../../../generated/api';
import { RepositoryCreateModalComponent } from '../../../shared/components/modals/repository-create-modal/repository-create-modal.component';
import { ProfileService } from '../../profile/service/profile.service';
import { RecentActivityComponent } from '../recent-activity/recent-activity.component';
import { RepositoryCardComponent } from '../repository-card/repository-card.component';
import { SecurityOverviewCardComponent } from '../security-overview-card/security-overview-card.component';
import { UsageService } from '../service/usage.service';
import { TotalDiskComponent } from '../total-disk/total-disk.component';
import { WelcomeCardComponent } from '../welcome-card/welcome-card.component';

/** How many repositories the recent activity card lists. */
export const RECENT_REPOSITORY_COUNT = 6;

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
  public repoListInfos: RepoListInfo[] = [];
  public createRepoModal: boolean;
  public isAdmin = false;

  constructor(
    private readonly repoCollectionControllerService: RepoCollectionControllerService,
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
        this.cdRef.markForCheck();
      },
      error: () => {},
    });

    this.profileService.get().subscribe({
      next: (profile) => {
        this.isAdmin = profile.role === 'ADMIN';
        this.cdRef.markForCheck();
      },
      error: () => {},
    });

    // The counts and the recent repositories are open to every role: a USER sees the same numbers as
    // an ADMIN, not a card of zeros.
    this.fetchRepoCounts();
    this.fetchRecentRepos();
  }

  public openCreateRepo(): void {
    if (this.isAdmin) {
      this.createRepoModal = true;
    }
  }

  /** One request for all nine types. A count that cannot be fetched stays at zero. */
  private fetchRepoCounts(): void {
    this.repoCollectionControllerService.getRepoCounts().subscribe({
      next: (response) => {
        const counts = response.data ?? {};
        this.npmRegistryCount = counts[RepoType.Npm] ?? 0;
        this.pypiRepoCount = counts[RepoType.Pypi] ?? 0;
        this.mavenRepoCount = counts[RepoType.Maven] ?? 0;
        this.dockerRepoCount = counts[RepoType.Docker] ?? 0;
        this.cargoRepoCount = counts[RepoType.Cargo] ?? 0;
        this.golangRepoCount = counts[RepoType.Golang] ?? 0;
        this.helmRepoCount = counts[RepoType.Helm] ?? 0;
        this.nugetRepoCount = counts[RepoType.Nuget] ?? 0;
        this.rubyRepoCount = counts[RepoType.Ruby] ?? 0;
        this.cdRef.markForCheck();
      },
      error: () => {},
    });
  }

  /**
   * The newest repositories of every type, in one request. Each item already carries its disk usage,
   * so no per-repository usage call is made (that call needs the MANAGE permission a USER lacks).
   */
  private fetchRecentRepos(): void {
    this.repoCollectionControllerService
      .listRepos(undefined, undefined, 0, RECENT_REPOSITORY_COUNT, ['createdAt,desc'])
      .subscribe({
        next: (response) => {
          this.repoListInfos = response.data?.content ?? [];
          this.cdRef.markForCheck();
        },
        // The HTTP error interceptor already shows the failure; the card stays empty.
        error: () => {},
      });
  }
}
