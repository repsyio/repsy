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

import { environment } from '../../../../../../../environments/environment';
import { ReleaseClassifierInfo, ReleaseDetail, RepoPermissionInfo, RepoType } from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { MarkdownComponent } from '../../../../../shared/components/markdown/markdown.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/services/breadcrumb-security-link.service';
import { externalHttpUrl } from '../../../../../shared/utils/external-url.utils';
import {
  deleteVersionAndCheckLast$,
  landAfterVersionDelete,
  VERSION_PROBE_SIZE,
  VERSION_PROBE_SORT,
} from '../../../../../shared/utils/version-delete-landing.utils';
import { versionLoadError } from '../../../../../shared/utils/version-load-error.utils';
import { RepoLookupService } from '../../../repo-entry/repo-lookup.service';
import { PypiService } from '../../services/pypi.service';

type Classifiers = Record<string, [string]>;

@Component({
  selector: 'app-pypi-packages-version-detail',
  standalone: true,
  imports: [
    CommonModule,
    CopyClipboardComponent,
    MarkdownComponent,
    NgOptimizedImage,
    SpinnerComponent,
    SecurityScanSectionComponent,
  ],
  templateUrl: './pypi-packages-version-detail.component.html',
})
export class PypiPackagesVersionDetailComponent implements OnDestroy {
  readonly securityRepoType = RepoType.Pypi;
  loading = true;
  baseUrl: string;
  error: string;
  packageName: string;
  versionName: string;
  installation: string;
  activeRepo: RepoPermissionInfo;
  private readonly repositoryChanges$: Subscription;
  versionInfo: ReleaseDetail;
  classifiers: Classifiers;

  /** The home page as a link target: http(s) only (RPS-1623), `null` leaves the anchor inert. */
  get homePageUrl(): string | null {
    return externalHttpUrl(this.versionInfo?.homePage);
  }

  constructor(
    private readonly pypiService: PypiService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly breadcrumbSecurityLinkService: BreadcrumbSecurityLinkService,
    private readonly repoLookupService: RepoLookupService,
  ) {
    this.classifiers = {};
    this.baseUrl = environment.repoBaseUrl;
    this.activeRepo = {} as RepoPermissionInfo;

    this.repositoryChanges$ = this.pypiService.repoChanges$.subscribe((registry: RepoPermissionInfo) => {
      if (registry && this.isRegistryForCurrentRoute(registry)) {
        this.activeRepo = Object.assign({}, registry);
        this.loadVersion();
      }
    });
    this.breadcrumbSecurityLinkService.show(RepoType.Pypi);
  }

  ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
    this.breadcrumbSecurityLinkService.clear();
  }

  private isRegistryForCurrentRoute(registry: RepoPermissionInfo): boolean {
    const currentRepo = this.repoLookupService.currentRepo;
    const matches = !!currentRepo && registry.repoName === currentRepo.repoName;

    if (!matches) {
      console.debug('Ignoring stale repo registry emission for a different repo', registry, currentRepo);
    }

    return matches;
  }

  loadVersion(): void {
    this.loading = true;
    this.error = null;

    this.packageName = this.route.snapshot.paramMap.get('packageName');
    this.versionName = this.route.snapshot.paramMap.get('version');

    this.installation = `pip install ${this.packageName}==${this.versionName} --extra-index-url ${this.baseUrl}/${this.activeRepo.repoName}/simple`;

    this.pypiService
      .fetchRelease(this.packageName, this.versionName)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (releaseInfo: ReleaseDetail) => {
          this.versionInfo = releaseInfo;
          releaseInfo.classifiers.forEach((c: ReleaseClassifierInfo) =>
            this.classifiers[c.classifier]
              ? this.classifiers[c.classifier].push(c.value)
              : (this.classifiers[c.classifier] = [c.value]),
          );

          releaseInfo.descriptionContentType = releaseInfo.descriptionContentType
            ? releaseInfo.descriptionContentType.split(';')[0].trim()
            : '';
        },
        error: (err: unknown) => {
          this.versionInfo = undefined;
          this.error = versionLoadError(err, this.versionName);
        },
      });
  }

  deleteVersion() {
    this.dangerModalService.show('Delete Release', 'Delete', () => {
      this.loading = true;
      deleteVersionAndCheckLast$(
        this.pypiService.fetchPackageReleasesLikeName(this.packageName, '', VERSION_PROBE_SORT, 0, VERSION_PROBE_SIZE),
        () => this.pypiService.deleteRelease(this.packageName, this.versionInfo.version),
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
          error: () => {},
        });
    });
  }
}
