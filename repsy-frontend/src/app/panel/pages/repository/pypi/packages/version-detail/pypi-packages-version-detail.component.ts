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
import { ReleaseClassifierInfo } from '../../dto/release-classifier-info';
import { ReleaseInfo } from '../../dto/release-info';
import { PypiService } from '../../service/pypi.service';

type Classifiers = Record<string, [string]>;

@Component({
  selector: 'app-pypi-packages-version-detail',
  standalone: true,
  imports: [CommonModule, CopyClipboardComponent, NgOptimizedImage, SpinnerComponent],
  templateUrl: './pypi-packages-version-detail.component.html',
})
export class PypiPackagesVersionDetailComponent implements OnDestroy {
  public loading = true;
  public baseUrl: string;
  public error: string;
  public packageName: string;
  public versionName: string;
  public installation: string;
  public activeRepo: RepoPermissionInfo;
  private readonly repositoryChanges$: Subscription;
  public versionInfo: ReleaseInfo;
  public classifiers: Classifiers;

  constructor(
    private readonly pypiService: PypiService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.classifiers = {};
    this.baseUrl = environment.repoBaseUrl;
    this.activeRepo = new RepoPermissionInfo();

    this.repositoryChanges$ = this.pypiService.repoChanges.subscribe((registry: RepoPermissionInfo) => {
      if (registry) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), registry);
        this.loadVersion();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadVersion(): void {
    this.loading = true;

    this.packageName = this.route.snapshot.paramMap.get('package');
    this.versionName = this.route.snapshot.paramMap.get('version');

    this.installation = `pip install hello-world --extra-index-url ${this.baseUrl}/${this.activeRepo.repoName}/simple`;

    this.pypiService
      .fetchRelease(this.packageName, this.versionName)
      .then((releaseInfo: ReleaseInfo) => {
        this.versionInfo = releaseInfo;
        releaseInfo.classifiers.forEach((c: ReleaseClassifierInfo) =>
          this.classifiers[c.classifier]
            ? this.classifiers[c.classifier].push(c.value)
            : (this.classifiers[c.classifier] = [c.value]),
        );

        releaseInfo.descriptionContentType = releaseInfo.descriptionContentType
          ? releaseInfo.descriptionContentType.split(';')[0].trim()
          : '';
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public deleteVersion() {
    this.dangerModalService.show('Delete Release', 'Delete', () => {
      this.loading = true;
      this.pypiService
        .deleteRelease(this.packageName, this.versionInfo.version)
        .then(() => {
          this.router.navigateByUrl(`/${this.activeRepo.repoName}`).then(() => {
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
