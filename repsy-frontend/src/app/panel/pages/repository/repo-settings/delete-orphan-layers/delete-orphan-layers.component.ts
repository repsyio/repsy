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

import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { DockerImageControllerService, RepoPermissionInfo } from '../../../../../../generated/api';

@Component({
  selector: 'app-delete-orphan-layers',
  templateUrl: './delete-orphan-layers.component.html',
  styleUrls: ['./delete-orphan-layers.component.css'],
  standalone: true,
  imports: [RouterLink],
})
export class DeleteOrphanLayersComponent {
  @Input() public activeRepository: RepoPermissionInfo;

  public deleting = false;

  constructor(
    private readonly dockerImageControllerService: DockerImageControllerService,
    private readonly dangerModalService: DangerModalService,
    private readonly toastService: ToastService,
  ) {}

  public deleteOrphanLayers(): void {
    this.dangerModalService.show(
      'Delete Orphan Layers',
      'Delete',
      () => {
        this.deleting = true;
        this.dockerImageControllerService
          .deleteOrphanLayers(this.activeRepository.repoName)
          .pipe(finalize(() => { this.deleting = false; }))
          .subscribe({
            next: () => this.toastService.show('Orphan layers deleted successfully', 'success'),
            error: () => {},
          });
      },
    );
  }
}
