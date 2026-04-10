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
import { Subscription } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { ModuleVersionListItem } from '../../dto/module-version-list-item';
import { GolangService } from '../../service/golang.service';

@Component({
  selector: 'app-golang-module-version-detail',
  standalone: true,
  imports: [CommonModule, CopyClipboardComponent, NgOptimizedImage, SpinnerComponent],
  templateUrl: './golang-module-version-detail.component.html',
})
export class GolangModuleVersionDetailComponent implements OnDestroy {
  public loading = true;
  public error: string;
  public modulePath: string;
  public versionName: string;
  public versionInfo: ModuleVersionListItem;
  public getCommand: string;
  public goEnvCommand: string;
  public goproxyEndpoints: { label: string; url: string }[];
  public activeRepo: RepoPermissionInfo;
  public readonly repoBaseUrl: string;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly golangService: GolangService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.repoBaseUrl = environment.repoBaseUrl;
    this.activeRepo = new RepoPermissionInfo();

    this.repositoryChanges$ = this.golangService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.modulePath = this.route.snapshot.queryParamMap.get('modulePath');
        this.versionName = this.route.snapshot.queryParamMap.get('version');

        if (!this.modulePath || !this.versionName) {
          this.router.navigate(['/' + this.activeRepo.repoName]);
          return;
        }

        this.getCommand = `GONOSUMDB=* GOPROXY=${this.repoBaseUrl}/${this.activeRepo.repoName},off go get ${this.modulePath}@${this.versionName}`;
        this.goEnvCommand = `go env -w GONOSUMDB="*" GOPROXY="${this.repoBaseUrl}/${this.activeRepo.repoName},off"`;
        this.goproxyEndpoints = [
          {
            label: '.info',
            url: `${this.repoBaseUrl}/${this.activeRepo.repoName}/${this.modulePath}/@v/${this.versionName}.info`,
          },
          {
            label: '.mod',
            url: `${this.repoBaseUrl}/${this.activeRepo.repoName}/${this.modulePath}/@v/${this.versionName}.mod`,
          },
          {
            label: '.zip',
            url: `${this.repoBaseUrl}/${this.activeRepo.repoName}/${this.modulePath}/@v/${this.versionName}.zip`,
          },
        ];
        this.fetchVersion();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public deleteVersion(): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.golangService
        .deleteModuleVersion(this.modulePath, this.versionName)
        .then(() => {
          this.router
            .navigate(['/' + this.activeRepo.repoName + '/modules'], {
              queryParams: { modulePath: this.modulePath },
            })
            .then(() => {
              this.toastService.show('Version deleted successfully', 'success');
            });
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.loading = false;
        });
    });
  }

  private fetchVersion(): void {
    this.loading = true;
    this.golangService
      .fetchModuleInfo(this.modulePath)
      .then((info) => {
        const found = info.versions.find((v) => v.version === this.versionName);
        if (!found) {
          this.error = `Version '${this.versionName}' not found`;
          return;
        }
        this.versionInfo = found;
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public get canManage(): boolean {
    return this.activeRepo?.canManage ?? false;
  }
}
