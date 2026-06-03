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
import { finalize } from 'rxjs/operators';

import { PackageVersionDetail, RepoPermissionInfo } from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
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
  public versionInfo: PackageVersionDetail;

  constructor(
    private readonly npmService: NpmService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.activeRegistry = {} as RepoPermissionInfo;

    this.registryChanges$ = this.npmService.repoChanges.subscribe((registry: RepoPermissionInfo) => {
      if (registry) {
        this.activeRegistry = Object.assign({}, registry);
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
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (packageVersionInfo: PackageVersionDetail) => {
          console.log('VERSION DETAIL RAW:', JSON.stringify(packageVersionInfo));
          this.versionInfo = packageVersionInfo;
          this.versionInfo.versionName = this.versionName;
        },
        error: () => {},
      });
  }

  public deleteVersion() {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.npmService
        .deletePackageVersion(this.packageName, this.scopeName, this.versionInfo.versionName)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.router.navigateByUrl(`/${this.activeRegistry.repoName}`).then(() => {
              this.toastService.show('Version deleted successfully', 'success');
            });
          },
          error: () => {},
        });
    });
  }
}
