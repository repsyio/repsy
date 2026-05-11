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
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';

import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ProtocolRepoControllerService, RepoInfo, RepoPermissionInfo } from '../../../../../../generated/api';

@Component({
  selector: 'app-delete-repo',
  templateUrl: './delete-repo.component.html',
  styleUrls: ['./delete-repo.component.css'],
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
})
export class DeleteRepoComponent {
  @Input() public activeRepository: RepoPermissionInfo;
  @Input() public repoType: string;

  public public: boolean;
  public loading: boolean;
  public visibilityForm: FormGroup;

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.visibilityForm = new FormGroup({
      publicRepository: new FormControl(false, [Validators.required]),
    });
  }

  public deleteRepo() {
    const successMsg = 'Repository deleted successfully';
    this.dangerModalService.show('Delete Repository', 'Delete', () => {
      this.loading = true;
      this.protocolRepoControllerService.deleteRepo({} as RepoInfo, {} as any, this.activeRepository.repoName).pipe(
        finalize(() => { this.loading = false; }),
      ).subscribe({
        next: () => {
          this.router.navigate(['/repositories']).then(() => {
            this.toastService.show(successMsg, 'success');
          });
        },
        error: () => {},
      });
    });
  }
}
