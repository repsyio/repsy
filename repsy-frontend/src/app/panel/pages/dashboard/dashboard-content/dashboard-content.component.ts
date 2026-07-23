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
import { forkJoin, map, of, switchMap } from 'rxjs';

import { RepoListInfo, RepoType, TotalUsageInfo } from '../../../../../generated/api';
import { ProtocolRepoControllerService } from '../../../../../generated/api';
import { RepositoryCreateModalComponent } from '../../../shared/components/modals/repository-create-modal/repository-create-modal.component';
import { RecentActivityComponent } from '../recent-activity/recent-activity.component';
import { RepositoryCardComponent } from '../repository-card/repository-card.component';
import { SecurityOverviewCardComponent } from '../security-overview-card/security-overview-card.component';
import { UsageService } from '../service/usage.service';
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

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly usageService: UsageService,
    private readonly cdRef: ChangeDetectorRef,
  ) {
    this.usageService
      .getTotalUsage()
      .subscribe((usage) => {
        Object.assign(this.usage, usage);
      });

    this.fetchRepoCounts();
    this.fetchRepoInfos();
  }

  public openCreateRepo(): void {
    this.createRepoModal = true;
  }

  private fetchRepoCounts(): void {
    this.protocolRepoControllerService
      .getCount(RepoType.Npm)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.npmRegistryCount = c;
        this.cdRef.markForCheck();
      });

    this.protocolRepoControllerService
      .getCount(RepoType.Pypi)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.pypiRepoCount = c;
        this.cdRef.markForCheck();
      });

    this.protocolRepoControllerService
      .getCount(RepoType.Maven)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.mavenRepoCount = c;
        this.cdRef.markForCheck();
      });

    this.protocolRepoControllerService
      .getCount(RepoType.Docker)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.dockerRepoCount = c;
        this.cdRef.markForCheck();
      });

    this.protocolRepoControllerService
      .getCount(RepoType.Cargo)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.cargoRepoCount = c;
        this.cdRef.markForCheck();
      });

    this.protocolRepoControllerService
      .getCount(RepoType.Golang)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.golangRepoCount = c;
        this.cdRef.markForCheck();
      });

    this.protocolRepoControllerService
      .getCount(RepoType.Helm)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.helmRepoCount = c;
        this.cdRef.markForCheck();
      });

    this.protocolRepoControllerService
      .getCount(RepoType.Nuget)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.nugetRepoCount = c;
        this.cdRef.markForCheck();
      });

    this.protocolRepoControllerService
      .getCount(RepoType.Ruby)
      .pipe(map((r) => r.data ?? 0))
      .subscribe((c) => {
        this.rubyRepoCount = c;
        this.cdRef.markForCheck();
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
              this.protocolRepoControllerService.getUsage(repo.name).pipe(
                map((r) => {
                  repo.diskUsage = r.data?.diskUsed?.value;
                  repo.type = repoType;
                  return repo;
                }),
              ),
            ),
          );
        }),
      )
      .subscribe((updatedRepos) => {
        this.repoListInfos = this.repoListInfos
          .concat(updatedRepos)
          .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
          .slice(0, 6);
        this.cdRef.markForCheck();
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
