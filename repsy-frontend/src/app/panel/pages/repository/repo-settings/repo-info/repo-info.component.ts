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

import { Component, Input, OnInit } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';

import {
  ProtocolRepoControllerService,
  RepoDescriptionForm,
  RepoPermissionInfo,
  RepoRenameForm,
} from '../../../../../../generated/api';
import { idFactory } from '../../../../../shared/util/unique-id';
import {
  DESCRIPTION_MAX_LENGTH,
  DESCRIPTION_MAX_MESSAGE,
  descriptionValidators,
} from '../../../../../shared/validators/description.validators';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { reservedRepoNameValidator } from '../../../../shared/util/reserved-repo-names';

@Component({
  selector: 'app-repo-info',
  templateUrl: './repo-info.component.html',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
})
export class RepoInfoComponent implements OnInit {
  /** Element ids of this instance: see `idFactory`. */
  public readonly id = idFactory('repo-info');

  @Input() public repoType: string;
  @Input() public activeRepository: RepoPermissionInfo;
  public renameForm: FormGroup;
  public descriptionForm: FormGroup;

  public loading = false;
  public private = false;
  public readonly descriptionMaxLength = DESCRIPTION_MAX_LENGTH;
  public readonly descriptionMaxMessage = DESCRIPTION_MAX_MESSAGE;

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.renameForm = new FormGroup({
      name: new FormControl('', [
        Validators.required,
        Validators.maxLength(25),
        Validators.pattern(/^[a-zA-Z0-9_][a-zA-Z0-9_\-]*$/),
        reservedRepoNameValidator(),
      ]),
    });

    this.descriptionForm = new FormGroup({
      description: new FormControl('', descriptionValidators()),
    });
  }

  ngOnInit(): void {
    this.renameForm?.get('name').setValue(this.activeRepository.repoName);
    this.descriptionForm?.get('description').setValue(this.activeRepository.description);
  }

  public renameRepo() {
    const form: RepoRenameForm = Object.assign({}, this.renameForm.value);

    this.dangerModalService.show('Rename Repository', 'Rename', () => {
      this.loading = true;
      this.renameForm.disable();

      this.protocolRepoControllerService
        .renameRepo(this.activeRepository.repoName, form)
        .pipe(
          finalize(() => {
            this.loading = false;
            this.renameForm.enable();
          }),
        )
        .subscribe({
          next: () => {
            this.router.navigate([this.renameForm.get('name').value, 'settings']);
            this.toastService.show('Repository renamed successfully', 'success');
          },
          error: () => {},
        });
    });
  }

  public updateRepoDescription() {
    const form: RepoDescriptionForm = Object.assign({}, this.descriptionForm.value);

    this.loading = true;
    this.renameForm.disable();

    this.protocolRepoControllerService
      .updateRepoDescription(this.activeRepository.repoName, form)
      .pipe(
        finalize(() => {
          this.loading = false;
          this.renameForm.enable();
        }),
      )
      .subscribe({
        next: () => {
          this.toastService.show('Repository description updated successfully', 'success');
          this.activeRepository.description = form.description;
          this.resetDescriptionForm();
        },
        error: () => {},
      });
  }

  public resetForms() {
    this.resetRenameForm();
    this.resetDescriptionForm();
  }

  private resetRenameForm() {
    this.renameForm?.reset();
    this.renameForm?.get('name').setValue(this.activeRepository.repoName);
  }

  private resetDescriptionForm() {
    this.descriptionForm?.reset();
    this.descriptionForm?.get('description').setValue(this.activeRepository.description);
  }
}
