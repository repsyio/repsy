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
import { GemVersionInfo, RepoPermissionInfo, RepoType } from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { externalHttpUrl } from '../../../../../shared/util/external-url.util';
import {
  deleteVersionAndCheckLast$,
  landAfterVersionDelete,
  VERSION_PROBE_SIZE,
  VERSION_PROBE_SORT,
} from '../../../../../shared/util/version-delete-landing.util';
import { versionLoadError } from '../../../../../shared/util/version-load-error.util';
import { RubyService } from '../../service/ruby.service';

@Component({
  selector: 'app-ruby-gems-version-detail',
  standalone: true,
  imports: [
    CommonModule,
    SpinnerComponent,
    CopyClipboardComponent,
    NgOptimizedImage,
    Highlight,
    HighlightLineNumbers,
    SecurityScanSectionComponent,
  ],
  templateUrl: './ruby-gems-version-detail.component.html',
})
export class RubyGemsVersionDetailComponent implements OnDestroy {
  readonly securityRepoType = RepoType.Ruby;
  loading = true;
  error: string;
  gemName: string;
  versionName: string;
  installCommand: string;
  gemfileSnippet = '';
  activeRepo: RepoPermissionInfo;
  gemVersion: GemVersionInfo;

  /** The homepage as a link target: http(s) only (RPS-1623), `null` leaves the anchor inert. */
  get homepageUrl(): string | null {
    return externalHttpUrl(this.gemVersion?.homepage);
  }
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly rubyService: RubyService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.rubyService.repoChanges$.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.loadVersion();
      }
    });
  }

  ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  loadVersion(): void {
    const gemName = this.route.snapshot.paramMap.get('packageName');
    const version = this.route.snapshot.paramMap.get('version');
    if (!gemName || !version) {
      this.loading = false;
      return;
    }
    this.gemName = gemName;
    this.versionName = version;
    const repoUrl = `${environment.repoBaseUrl}/${this.activeRepo.repoName}/`;
    this.installCommand = `gem install ${gemName} -v ${version} --source ${repoUrl}`;

    this.loading = true;
    this.error = null;
    this.rubyService
      .fetchGemVersion(gemName, version)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (gemVersion) => {
          this.gemVersion = gemVersion;
          this.gemfileSnippet = this.buildGemfileSnippet(repoUrl, gemName, version);
          this.error = null;
        },
        error: (err: unknown) => {
          this.gemVersion = undefined;
          this.error = versionLoadError(err, this.versionName);
        },
      });
  }

  deleteVersion(): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      deleteVersionAndCheckLast$(
        this.rubyService.fetchGemVersions(this.gemName, '', VERSION_PROBE_SORT, 0, VERSION_PROBE_SIZE),
        () => this.rubyService.deleteGemVersion(this.gemName, this.versionName, this.gemVersion?.platform ?? 'ruby'),
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

  private buildGemfileSnippet(repoUrl: string, gemName: string, version: string): string {
    return `source "${repoUrl}" do\n  gem "${gemName}", "${version}"\nend`;
  }
}
