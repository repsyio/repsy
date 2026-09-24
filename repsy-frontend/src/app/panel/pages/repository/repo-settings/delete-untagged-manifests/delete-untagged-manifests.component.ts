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

import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';

import {
  DockerImageControllerService,
  RepoPermissionInfo,
  UntaggedManifestCleanupResult,
} from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ByteFormatter } from '../../../../shared/util/byte-formatter';

@Component({
  selector: 'app-delete-untagged-manifests',
  templateUrl: './delete-untagged-manifests.component.html',
  styleUrls: ['./delete-untagged-manifests.component.css'],
  standalone: true,
  imports: [RouterLink],
})
export class DeleteUntaggedManifestsComponent {
  @Input() public activeRepository: RepoPermissionInfo;

  public deleting = false;

  constructor(
    private readonly dockerImageControllerService: DockerImageControllerService,
    private readonly dangerModalService: DangerModalService,
    private readonly toastService: ToastService,
  ) {}

  public deleteUntaggedManifests(): void {
    this.dangerModalService.showWithMessage(
      'Delete Untagged Manifests',
      'Delete',
      'Manifests that no tag points to will be deleted and stop being pullable by digest. ' +
        'The layers that only they used are deleted afterwards. This cannot be undone.',
      () => {
        this.deleting = true;
        this.dockerImageControllerService
          .deleteUntaggedManifests(this.activeRepository.repoName)
          .pipe(
            finalize(() => {
              this.deleting = false;
            }),
          )
          .subscribe({
            next: (response) => this.toastService.show(this.summary(response?.data), 'success'),
            error: () => {},
          });
      },
    );
  }

  private summary(result: UntaggedManifestCleanupResult | undefined): string {
    const manifests = result?.deletedManifests ?? 0;
    const layers = result?.orphanLayersScheduled ?? 0;

    if (manifests === 0 && layers === 0) {
      return 'No untagged manifests to delete';
    }

    const freed = ByteFormatter.formatBytes((result?.freedManifestBytes ?? 0) + (result?.orphanLayerBytes ?? 0));

    return `Deleted ${manifests} untagged ${manifests === 1 ? 'manifest' : 'manifests'} and ${layers} unused ${
      layers === 1 ? 'layer' : 'layers'
    } (${freed} freed)`;
  }
}
