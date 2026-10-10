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
import { ActivatedRoute, Router } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { HighlightLineNumbers } from 'ngx-highlightjs/line-numbers';
import { Subscription } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../../environments/environment';
import { HelmChartDetail, RepoPermissionInfo, RepoType } from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { ByteFormatter } from '../../../../../shared/util/byte-formatter';
import {
  deleteVersionAndCheckLast$,
  landAfterVersionDelete,
  VERSION_PROBE_SIZE,
  VERSION_PROBE_SORT,
} from '../../../../../shared/util/version-delete-landing.util';
import { versionLoadError } from '../../../../../shared/util/version-load-error.util';
import { HelmService } from '../../service/helm.service';

@Component({
  selector: 'app-helm-charts-version-detail',
  standalone: true,
  imports: [
    CommonModule,
    SpinnerComponent,
    CopyClipboardComponent,
    NgOptimizedImage,
    HighlightLineNumbers,
    Highlight,
    SecurityScanSectionComponent,
  ],
  templateUrl: './helm-charts-version-detail.component.html',
})
export class HelmChartsVersionDetailComponent implements OnDestroy {
  readonly securityRepoType = RepoType.Helm;
  loading = true;
  error: string;
  chartName: string;
  versionName: string;
  chart: HelmChartDetail;
  classicInstallCommand: string;
  ociPullCommand: string;
  chartYaml = '';
  formattedSize = '';
  activeRepo: RepoPermissionInfo = {} as RepoPermissionInfo;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly helmService: HelmService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.repositoryChanges$ = this.helmService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.loadDetail();
      }
    });
  }

  ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  deleteVersion(): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      // Deleting the last version removes the chart, so its versions page would answer 404: the page
      // to land on is decided from the versions the chart has right before the delete.
      deleteVersionAndCheckLast$(
        this.helmService.fetchChartVersions(this.chartName, '', VERSION_PROBE_SORT, 0, VERSION_PROBE_SIZE),
        () => this.helmService.deleteChart(this.chartName, this.versionName),
      )
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: (wasLastVersion) => {
            landAfterVersionDelete(
              this.router,
              this.route,
              this.toastService,
              this.activeRepo.repoName,
              wasLastVersion,
            );
          },
          // The error interceptor has already shown the failure to the user.
          error: () => {},
        });
    });
  }

  private loadDetail(): void {
    const name = this.route.snapshot.paramMap.get('packageName');
    const version = this.route.snapshot.paramMap.get('version');
    if (!name || !version) {
      this.loading = false;
      return;
    }
    this.chartName = name;
    this.versionName = version;

    const baseUrl = environment.repoBaseUrl;
    const repoName = this.activeRepo.repoName;
    this.classicInstallCommand = `helm install ${name} ${repoName}/${name} --version ${version}`;
    const ociHost = baseUrl.replace(/^https?:\/\//, '');
    this.ociPullCommand = `helm pull oci://${ociHost}/${repoName}/${name} --version ${version}`;
    this.loading = true;
    this.error = null;

    this.helmService
      .fetchChartDetail(name, version)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (detail) => {
          this.chart = detail;
          this.error = null;
          this.formattedSize = ByteFormatter.formatBytes(this.chart.size);
          this.chartYaml = [
            'apiVersion: v2',
            `name: ${this.chart.name}`,
            `version: ${this.chart.version}`,
            this.chart.description ? `description: ${this.chart.description}` : null,
            this.chart.appVersion ? `appVersion: "${this.chart.appVersion}"` : null,
            this.chart.type ? `type: ${this.chart.type}` : null,
          ]
            .filter(Boolean)
            .join('\n');
        },
        error: (err: unknown) => {
          this.chart = undefined;
          this.error = versionLoadError(err, this.versionName);
        },
      });
  }
}
