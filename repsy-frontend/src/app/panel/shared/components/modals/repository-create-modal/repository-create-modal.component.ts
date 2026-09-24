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

import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs/operators';

import { RepoCollectionControllerService, RepoCreateRequest, RepoListInfo } from '../../../../../../generated/api';
import { idFactory } from '../../../../../shared/util/unique-id';
import {
  DESCRIPTION_MAX_LENGTH,
  DESCRIPTION_MAX_MESSAGE,
  descriptionValidators,
} from '../../../../../shared/validators/description.validators';
import { DialogDirective } from '../../../directives/dialog.directive';
import { RepoType } from '../../../dto/repo/repo-type';
import { toApiRepoType } from '../../../util/repo-api-type';
import { reservedRepoNameValidator } from '../../../util/reserved-repo-names';
import { SelectorComponent } from '../../selector/selector.component';
import { ToastService } from '../../toast/toast.service';
import { ToggleComponent } from '../../toggle/toggle.component';

@Component({
  selector: 'app-repository-modal',
  standalone: true,
  imports: [DialogDirective, SelectorComponent, ReactiveFormsModule, ToggleComponent],
  templateUrl: './repository-create-modal.component.html',
  styleUrl: './repository-create-modal.component.css',
})
export class RepositoryCreateModalComponent implements OnInit {
  /** Element ids of this instance: see `idFactory`. */
  public readonly id = idFactory('repo-create');

  @Output() openChange = new EventEmitter<boolean>();
  /** The repository the server created, as the list shows it. */
  @Output() created = new EventEmitter<RepoListInfo | undefined>();
  @Input() public open: boolean;
  @Input() selectedOption: RepoType;

  public options = [
    RepoType.DOCKER,
    RepoType.MAVEN,
    RepoType.NPM,
    RepoType.PYPI,
    RepoType.CARGO,
    RepoType.GOLANG,
    RepoType.HELM,
    RepoType.NUGET,
    RepoType.RUBY,
  ];
  public form: FormGroup;

  public loading = false;
  public readonly descriptionMaxLength = DESCRIPTION_MAX_LENGTH;
  public readonly descriptionMaxMessage = DESCRIPTION_MAX_MESSAGE;

  constructor(
    private readonly repoCollectionControllerService: RepoCollectionControllerService,
    private readonly fb: FormBuilder,
    private readonly router: Router,
    private readonly toastService: ToastService,
  ) {}

  public ngOnInit(): void {
    if (this.selectedOption == null) {
      this.selectedOption = RepoType.DOCKER;
    }

    this.form = this.fb.group({
      name: [
        '',
        [
          Validators.required,
          Validators.maxLength(25),
          Validators.pattern(/^[a-zA-Z0-9_][a-zA-Z0-9_\-]*$/),
          reservedRepoNameValidator(),
        ],
      ],
      privateRepo: [true],
      description: ['', descriptionValidators()],
    });
  }

  public closeModal() {
    this.form.reset();
    this.form.get('privateRepo').setValue(true);
    this.openChange.emit(false);
  }

  public selectOption(option: string) {
    this.selectedOption = option as RepoType;
  }

  public createRepo() {
    this.loading = true;
    this.form.disable();

    const type = toApiRepoType(this.selectedOption);
    const body: RepoCreateRequest = { ...this.form.getRawValue(), type };

    this.repoCollectionControllerService
      .createRepository(body)
      .pipe(
        finalize(() => {
          this.form.enable();
          this.loading = false;
        }),
      )
      .subscribe({
        next: (response) => {
          this.router.navigate(['/repositories']).then(() => {
            this.toastService.show('Repository created successfully', 'success');
          });

          this.created.emit(response.data);
          this.closeModal();
        },
        error: () => {},
      });
  }
}
