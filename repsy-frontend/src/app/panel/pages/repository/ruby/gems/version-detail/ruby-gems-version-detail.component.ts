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
import { GemVersionInfo, RepoPermissionInfo } from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { RubyService } from '../../service/ruby.service';

@Component({
  selector: 'app-ruby-gems-version-detail',
  standalone: true,
  imports: [CommonModule, SpinnerComponent, CopyClipboardComponent, NgOptimizedImage, Highlight, HighlightLineNumbers],
  templateUrl: './ruby-gems-version-detail.component.html',
})
export class RubyGemsVersionDetailComponent implements OnDestroy {
  public loading = true;
  public error: string;
  public gemName: string;
  public versionName: string;
  public installCommand: string;
  public gemfileSnippet = '';
  public activeRepo: RepoPermissionInfo;
  public gemVersion: GemVersionInfo;
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly rubyService: RubyService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.rubyService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.loadVersion();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadVersion(): void {
    const gemName = this.route.snapshot.paramMap.get('gem');
    const version = this.route.snapshot.paramMap.get('version');
    if (!gemName || !version) {
      this.loading = false;
      return;
    }
    this.gemName = gemName;
    this.versionName = version;
    const repoUrl = `${environment.repoBaseUrl}/rubygems/${this.activeRepo.repoName}/`;
    this.installCommand = `gem install ${gemName} -v ${version} --source ${repoUrl}`;

    this.loading = true;
    this.rubyService
      .fetchGemVersion(gemName, version)
      .pipe(finalize(() => { this.loading = false; }))
      .subscribe({
        next: (gemVersion) => {
          this.gemVersion = gemVersion;
          this.gemfileSnippet = this.buildGemfileSnippet(repoUrl, gemName, version);
          this.error = null;
        },
        error: () => {},
      });
  }

  public deleteVersion(): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.rubyService
        .deleteGemVersion(this.gemName, this.versionName, this.gemVersion?.platform ?? 'ruby')
        .pipe(finalize(() => { this.loading = false; }))
        .subscribe({
          next: () => {
            this.router.navigate(['../..'], { relativeTo: this.route }).then(() => {
              this.toastService.show('Version deleted successfully', 'success');
            });
          },
          error: () => {},
        });
    });
  }

  private buildGemfileSnippet(repoUrl: string, gemName: string, version: string): string {
    return `source "${repoUrl}" do\n  gem "${gemName}", "${version}"\nend`;
  }
}
