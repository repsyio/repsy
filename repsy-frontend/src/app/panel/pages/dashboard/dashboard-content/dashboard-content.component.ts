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

import { RepositoryCreateModalComponent } from '../../../shared/components/modals/repository-create-modal/repository-create-modal.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { RepoListInfo } from '../../../shared/dto/repo/repo-list-info';
import { RepoType } from '../../../shared/dto/repo/repo-type';
import { RepoUsageInfo } from '../../../shared/dto/repo-usage-info';
import { TotalUsageInfo } from '../../../shared/dto/total-usage-info';
import { StatsService } from '../../../shared/service/stats.service';
import { RecentActivityComponent } from '../recent-activity/recent-activity.component';
import { RepositoryCardComponent } from '../repository-card/repository-card.component';
import { TotalDiskComponent } from '../total-disk/total-disk.component';
import { WelcomeCardComponent } from '../welcome-card/welcome-card.component';

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
    RouterModule,
    RepositoryCreateModalComponent,
  ],
  templateUrl: './dashboard-content.component.html',
})
export class DashboardContentComponent {
  public username = '';
  public usage: TotalUsageInfo = new TotalUsageInfo();
  public mavenRepoCount = 0;
  public npmRegistryCount = 0;
  public pypiRepoCount = 0;
  public dockerRepoCount = 0;
  public golangRepoCount = 0;
  public repositories: Repository[] = [];
  public repoListInfos: RepoListInfo[] = [];
  public createRepoModal: boolean;

  constructor(
    private readonly statsService: StatsService,
    private cdRef: ChangeDetectorRef,
    private readonly toastService: ToastService,
  ) {
    this.statsService.getTotalUsage().then((usage) => {
      if (usage === null) {
        return;
      }
      Object.assign(this.usage, usage);
    });

    this.fetchRepoCounts();
    this.fetchRepoInfos();
  }

  public async fetchRepoInfo(
    repoType: RepoType,
    listFetcher: () => Promise<RepoListInfo[]>,
    usageFetcher: (name: string) => Promise<RepoUsageInfo>,
  ) {
    const repos = await listFetcher();
    const updatedRepos: RepoListInfo[] = [];

    for (const repo of repos) {
      try {
        const usage = await usageFetcher(repo.name);
        repo.diskUsed = usage.diskUsed;
        repo.type = repoType;
        updatedRepos.push(repo);
      } catch (error) {
        this.toastService.show(error, 'error');
      }
    }

    this.repoListInfos = this.repoListInfos
      .concat(updatedRepos)
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(0, 6);

    this.cdRef.markForCheck();
  }

  public openCreateRepo() {
    this.createRepoModal = true;
  }

  private fetchRepoCounts() {
    this.statsService.getNpmRegistryCount().then((c) => {
      this.npmRegistryCount = c;
      this.cdRef.markForCheck();
    });

    this.statsService.getPypiRepoCount().then((c) => {
      this.pypiRepoCount = c;
      this.cdRef.markForCheck();
    });

    this.statsService.getMavenRepoCount().then((c) => {
      this.mavenRepoCount = c;
      this.cdRef.markForCheck();
    });

    this.statsService.getDockerRepoCount().then((c) => {
      this.dockerRepoCount = c;
      this.cdRef.markForCheck();
    });

    this.statsService.getGolangRepoCount().then((c) => {
      this.golangRepoCount = c;
      this.cdRef.markForCheck();
    });
  }

  private fetchRepoInfos() {
    this.fetchRepoInfo(
      RepoType.MAVEN,
      this.statsService.getMavenRepoInfo.bind(this.statsService),
      this.statsService.fetchMavenRepositoryUsage.bind(this.statsService),
    );
    this.fetchRepoInfo(
      RepoType.NPM,
      this.statsService.getNpmRepoInfo.bind(this.statsService),
      this.statsService.fetchNpmRepositoryUsage.bind(this.statsService),
    );
    this.fetchRepoInfo(
      RepoType.PYPI,
      this.statsService.getPypiRepoInfo.bind(this.statsService),
      this.statsService.fetchPypiRepositoryUsage.bind(this.statsService),
    );
    this.fetchRepoInfo(
      RepoType.DOCKER,
      this.statsService.getDockerRepoInfo.bind(this.statsService),
      this.statsService.fetchDockerRepositoryUsage.bind(this.statsService),
    );
    this.fetchRepoInfo(
      RepoType.GOLANG,
      this.statsService.getGolangRepoInfo.bind(this.statsService),
      this.statsService.fetchGolangRepositoryUsage.bind(this.statsService),
    );
  }
}
