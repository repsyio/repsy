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

import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { PackageVersionInfo } from '../../dto/package-version-info';
import { NpmService } from '../../service/npm.service';

@Component({
  selector: 'app-npm-packages-version-detail',
  standalone: true,
  imports: [CommonModule, CopyClipboardComponent, NgOptimizedImage, SpinnerComponent],
  templateUrl: './npm-packages-version-detail.component.html',
})
export class NpmPackagesVersionDetailComponent implements OnDestroy {
  public loading = true;
  public scopeName: string;
  public packageName: string;
  public versionName: string;
  public installation: string;
  public error: string;
  public activeRegistry: RepoPermissionInfo;
  private readonly registryChanges$: Subscription;
  public versionInfo: PackageVersionInfo;

  constructor(
    private readonly npmService: NpmService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.activeRegistry = new RepoPermissionInfo();

    this.registryChanges$ = this.npmService.registryChanges.subscribe((registry: RepoPermissionInfo) => {
      if (registry) {
        this.activeRegistry = Object.assign(new RepoPermissionInfo(), registry);
        this.loadVersion();
      }
    });
  }

  public ngOnDestroy(): void {
    this.registryChanges$.unsubscribe();
  }

  public loadVersion(): void {
    this.loading = true;

    this.scopeName = this.route.snapshot.paramMap.get('scope');
    this.packageName = this.route.snapshot.paramMap.get('package');
    this.versionName = this.route.snapshot.paramMap.get('version');

    if (this.scopeName == '~') {
      this.scopeName = null;
    }

    this.installation = this.scopeName
      ? `npm install @${this.scopeName}/${this.packageName}`
      : `npm install ${this.packageName}`;

    this.npmService
      .fetchPackageVersion(this.packageName, this.scopeName, this.versionName)
      .then((packageVersionInfo: PackageVersionInfo) => {
        packageVersionInfo.createdAt = new Date(packageVersionInfo.createdAt);
        packageVersionInfo.fullName = packageVersionInfo.scopeName
          ? '@' + packageVersionInfo.scopeName + '/' + packageVersionInfo.packageName
          : packageVersionInfo.packageName;
        this.versionInfo = packageVersionInfo;
        this.versionInfo.versionName = this.versionName;
        this.loading = false;
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
        this.loading = false;
      })
  }

  public deleteVersion() {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.npmService
        .deletePackageVersion(this.packageName, this.scopeName, this.versionInfo.versionName)
        .then(() => {
          this.router.navigateByUrl(`/${this.activeRegistry.repoName}`).then(() => {
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
}
