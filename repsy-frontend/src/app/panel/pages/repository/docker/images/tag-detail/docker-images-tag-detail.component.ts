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
import { Component } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { HighlightLineNumbers } from 'ngx-highlightjs/line-numbers';
import { Subscription } from 'rxjs';

import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { getRepoDomain } from '../../docker-repo-util';
import { TagInfo } from '../../dto/tag-info';
import { DockerService } from '../../service/docker.service';

type Classifiers = Record<string, [string]>;

@Component({
  selector: 'app-docker-tag-detail',
  standalone: true,
  imports: [CommonModule, CopyClipboardComponent, NgOptimizedImage, SpinnerComponent, HighlightLineNumbers, Highlight],
  templateUrl: './docker-images-tag-detail.component.html',
})
export class DockerImagesTagDetailComponent {
  public loading = true;
  public imageName: string;
  public tagName: string;
  public installText: string;
  public manifestText: string;
  public configText: string;
  public error: string;
  public tagInfo: TagInfo;
  public activeRepo: RepoPermissionInfo;
  public classifiers: Classifiers;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly dockerService: DockerService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.classifiers = {};
    this.activeRepo = new RepoPermissionInfo();

    this.repositoryChanges$ = this.dockerService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.imageName = this.route.snapshot.paramMap.get('image');
        this.tagName = this.route.snapshot.paramMap.get('tag');
        this.loadTag();
      }
    });
  }

  public loadTag(): void {
    this.loading = true;

    this.dockerService
      .fetchTag(this.imageName, this.tagName)
      .then((tagInfo: TagInfo) => {
        this.installText = `docker pull ${getRepoDomain()}/${this.activeRepo.repoName}/${tagInfo.imageName}:${tagInfo.name}`;

        this.tagInfo = tagInfo;
        this.loadManifestText(tagInfo.digest);
        this.loadConfigText(tagInfo.configDigest);
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public loadManifestText(digest: string): void {
    const imageName = this.route.snapshot.paramMap.get('image');

    this.dockerService.fetchManifestText(imageName, digest).then((manifestText: string) => {
      this.manifestText = manifestText;
    });
  }

  public loadConfigText(configDigest: string): void {
    if (configDigest === null) {
      // Multiplatform
      return;
    }

    const imageName = this.route.snapshot.paramMap.get('image');

    this.dockerService.fetchConfigText(imageName, configDigest).then((configText: string) => {
      this.configText = JSON.stringify(JSON.parse(configText), null, 2);
    });
  }

  public deleteTag() {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.dockerService
        .deleteTag(this.imageName, this.tagName)
        .then(() => {
          this.router.navigateByUrl(`/${this.activeRepo.repoName}`).then(() => {
            this.toastService.show('Tag deleted successfully', 'success');
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
