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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import moment from 'moment';
import { Subscription } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { DropdownComponent } from '../../../../../shared/components/dropdown/dropdown.component';
import { EmptyListComponent } from '../../../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../../../shared/components/tooltip/tooltip.component';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { HelmConfigComponent } from '../../config/helm-config.component';
import { HelmChartVersionItem } from '../../dto/helm-chart-version-item';
import { HelmService } from '../../service/helm.service';

@Component({
  selector: 'app-helm-charts-version-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    SpinnerComponent,
    HelmConfigComponent,
    DropdownComponent,
    TooltipComponent,
    EmptyListComponent,
    NgOptimizedImage,
  ],
  templateUrl: './helm-charts-version-list.component.html',
})
export class HelmChartsVersionListComponent implements OnDestroy {
  public loading = true;
  public showConfig = false;
  public error: string;
  public chartName: string;
  public versions: HelmChartVersionItem[] = [];
  public activeRepo: RepoPermissionInfo;
  public readonly baseUrl: string;
  public readonly username: string;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly authService: AuthService,
    private readonly helmService: HelmService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.baseUrl = environment.repoBaseUrl;
    this.username = this.authService.username;
    this.activeRepo = new RepoPermissionInfo();
    this.repositoryChanges$ = this.helmService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.chartName = this.route.snapshot.paramMap.get('name');
        this.fetchVersions();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public openConfig(open: boolean): void {
    this.showConfig = open;
  }

  public refreshPage(): void {
    this.fetchVersions();
  }

  public timeAgo(date: string): string {
    return moment(date).fromNow();
  }

  public formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  }

  public deleteVersion(version: HelmChartVersionItem): void {
    const isLastVersion = this.versions.length === 1;
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.helmService
        .deleteChart(this.chartName, version.version)
        .then(() => {
          this.toastService.show('Version deleted successfully', 'success');
          if (isLastVersion) {
            this.router.navigate(['..'], { relativeTo: this.route });
          } else {
            this.fetchVersions();
          }
        })
        .catch((err: string) => {
          this.loading = false;
          this.toastService.show(err, 'error');
        });
    });
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }

  private fetchVersions(): void {
    this.loading = true;
    this.helmService
      .getChartVersions(this.chartName)
      .then((versions: HelmChartVersionItem[]) => {
        this.versions = versions;
        this.error = null;
      })
      .catch((err: string) => {
        if (err === 'Chart is not Found.') {
          this.router.navigateByUrl(`/${this.activeRepo.repoName}`);
          return;
        }
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }
}
