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

import { RepoPermissionInfo, RepoType, TagDetail } from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/service/breadcrumb-security-link.service';
import { versionLoadError } from '../../../../../shared/util/version-load-error.util';
import { RepoLookupService } from '../../../repo-entry/repo-lookup.service';
import { getRepoDomain } from '../../docker-repo-util';
import { DockerService } from '../../service/docker.service';

type Classifiers = Record<string, [string]>;

@Component({
  selector: 'app-docker-images-tag-detail',
  standalone: true,
  imports: [
    CommonModule,
    CopyClipboardComponent,
    NgOptimizedImage,
    SpinnerComponent,
    HighlightLineNumbers,
    Highlight,
    SecurityScanSectionComponent,
  ],
  templateUrl: './docker-images-tag-detail.component.html',
})
export class DockerImagesTagDetailComponent implements OnDestroy {
  readonly securityRepoType = RepoType.Docker;
  loading = true;
  imageName: string;
  tagName: string;
  installText: string;
  manifestText: string;
  configText: string;
  error: string;
  tagInfo: TagDetail;
  activeRepo: RepoPermissionInfo;
  classifiers: Classifiers;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly dockerService: DockerService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly breadcrumbSecurityLinkService: BreadcrumbSecurityLinkService,
    private readonly repoLookupService: RepoLookupService,
  ) {
    this.classifiers = {};
    this.activeRepo = {} as RepoPermissionInfo;

    this.repositoryChanges$ = this.dockerService.repoChanges$.subscribe((repo: RepoPermissionInfo) => {
      if (repo && this.isRegistryForCurrentRoute(repo)) {
        this.activeRepo = Object.assign({}, repo);
        this.imageName = this.route.snapshot.paramMap.get('imageName');
        this.tagName = this.route.snapshot.paramMap.get('tagName');
        this.loadTag();
      }
    });
    this.breadcrumbSecurityLinkService.show(RepoType.Docker);
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

  loadTag(): void {
    this.loading = true;
    this.error = null;

    this.dockerService
      .fetchTag(this.imageName, this.tagName)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (tagInfo: TagDetail) => {
          this.installText = `docker pull ${getRepoDomain()}/${this.activeRepo.repoName}/${tagInfo.imageName}:${tagInfo.name}`;
          this.tagInfo = tagInfo;
          this.loadManifestText(tagInfo.digest);
          this.loadConfigText(tagInfo.configDigest);
        },
        error: (err: unknown) => {
          this.tagInfo = undefined;
          this.error = versionLoadError(err, this.tagName);
        },
      });
  }

  loadManifestText(digest: string): void {
    const imageName = this.route.snapshot.paramMap.get('imageName');

    this.dockerService.fetchManifestText(imageName, digest).subscribe({
      next: (manifestText: string) => {
        this.manifestText = manifestText;
      },
      error: () => {},
    });
  }

  /**
   * An image index (a multi-platform tag) has no config of its own: the API leaves `configDigest` out, it does not send
   * `null`, so the absence is `undefined` as much as `null` (RPS-1627). Its platforms' configs are in their manifests.
   */
  get hasConfig(): boolean {
    return !!this.tagInfo?.configDigest;
  }

  loadConfigText(configDigest: string | null | undefined): void {
    if (!configDigest) {
      return;
    }

    const imageName = this.route.snapshot.paramMap.get('imageName');

    this.dockerService.fetchConfigText(imageName, configDigest).subscribe({
      next: (configText: string) => {
        this.configText = JSON.stringify(JSON.parse(configText), null, 2);
      },
      error: () => {},
    });
  }

  deleteTag() {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.dockerService
        .deleteTag(this.imageName, this.tagName)
        .pipe(
          finalize(() => {
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            // The image's tag list, also after the last tag: deleting a tag never deletes the image
            // (its manifest stays pullable by digest), and that page says what is left (RPS-1288).
            this.router.navigateByUrl(`/${this.activeRepo.repoName}/${this.imageName}`).then(() => {
              this.toastService.show('Tag deleted successfully', 'success');
            });
          },
          error: () => {},
        });
    });
  }
}
