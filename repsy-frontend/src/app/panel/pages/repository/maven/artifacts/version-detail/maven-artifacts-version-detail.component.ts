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

import { NgOptimizedImage } from '@angular/common';
import { Component, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { HighlightLineNumbers } from 'ngx-highlightjs/line-numbers';
import { Subscription } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { ArtifactVersionInfo } from '../../dto/artifact-version-info';
import { MavenService } from '../../service/maven.service';

@Component({
  selector: 'app-maven-artifacts-version-detail',
  standalone: true,
  imports: [CopyClipboardComponent, NgOptimizedImage, SpinnerComponent, HighlightLineNumbers, Highlight],
  templateUrl: './maven-artifacts-version-detail.component.html',
})
export class MavenArtifactsVersionDetailComponent implements OnDestroy {
  public loading = true;
  public baseUrl: string;
  public groupName: string;
  public artifactName: string;
  public versionName: string;
  public error: string;
  public activeRepo: RepoPermissionInfo;
  public version: ArtifactVersionInfo;
  public mavenDependencyHtml: string;
  public gradleDependencyHtml: string;
  public gradleKotlinDependencyHtml: string;
  public sbtDependencyHtml: string;
  public ivyDependencyHtml: string;
  public groovyDependencyHtml: string;
  public leiningenDependencyHtml: string;
  public buildrDependencyHtml: string;
  public purlDependencyHtml: string;
  public bazelDependencyHtml: string;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly mavenService: MavenService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly dangerModalService: DangerModalService,
    private readonly toastService: ToastService,
  ) {
    this.baseUrl = environment.apiBaseUrl;
    this.activeRepo = new RepoPermissionInfo();

    this.repositoryChanges$ = this.mavenService.repoChanges.subscribe((registry: RepoPermissionInfo) => {
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

    this.groupName = this.route.snapshot.paramMap.get('group');
    this.artifactName = this.route.snapshot.paramMap.get('artifact');
    this.versionName = this.route.snapshot.paramMap.get('version');

    this.mavenService
      .getArtifactVersion(this.groupName, this.artifactName, this.versionName)
      .then((data) => {
        this.version = data;
        this.mavenDependencyHtml = `<dependency>
  <groupId>${this.version.artifactGroupName}</groupId>
  <artifactId>${this.version.artifactName}</artifactId>
  <version>${this.version.artifactVersionName}</version>
</dependency>`;
        this.gradleDependencyHtml = `implementation '${this.version.artifactGroupName}:${this.version.artifactName}:${this.version.artifactVersionName}'`;
        this.gradleKotlinDependencyHtml = `implementation("${this.version.artifactGroupName}:${this.version.artifactName}:${this.version.artifactVersionName}")`;
        this.sbtDependencyHtml = `libraryDependencies += "${this.version.artifactGroupName}" % "${this.version.artifactName}" % "${this.version.artifactVersionName}"`;
        this.ivyDependencyHtml = `<dependency org="${this.version.artifactGroupName}" name="${this.version.artifactName}" rev="${this.version.artifactVersionName}" />`;
        this.groovyDependencyHtml = `@Grapes(
  @Grab(group='${this.version.artifactGroupName}', module='${this.version.artifactName}', version='${this.version.artifactVersionName}')
)`;
        this.leiningenDependencyHtml = `[${this.version.artifactGroupName}/${this.version.artifactName} "${this.version.artifactVersionName}"]`;
        this.buildrDependencyHtml = `'${this.version.artifactGroupName}:${this.version.artifactName}:jar:${this.version.artifactVersionName}'`;
        this.purlDependencyHtml = `pkg:maven/${this.version.artifactGroupName}/${this.version.artifactName}@${this.version.artifactVersionName}`;
        this.bazelDependencyHtml = `maven_jar(
  name = "${this.version.artifactName}",
  artifact = "${this.version.artifactGroupName}:${this.version.artifactName}:${this.version.artifactVersionName}",
  sha1 = "calculating...",
)`;
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
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.mavenService
        .deleteVersion(this.groupName, this.artifactName, this.version.artifactVersionName)
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
