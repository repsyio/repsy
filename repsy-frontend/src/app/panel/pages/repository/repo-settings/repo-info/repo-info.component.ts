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

import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoDescriptionForm } from '../../../../shared/dto/repo/repo-description-form';
import { RepoNameForm } from '../../../../shared/dto/repo/repo-name-form';
import { RepoPermissionInfo } from '../../../../shared/dto/repo/repo-permission-info';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { CargoService } from '../../cargo/service/cargo.service';
import { DockerService } from '../../docker/service/docker.service';
import { MavenService } from '../../maven/service/maven.service';
import { NpmService } from '../../npm/service/npm.service';
import { PypiService } from '../../pypi/service/pypi.service';
import { GolangService } from '../../golang/service/golang.service';

@Component({
  selector: 'app-repo-info',
  templateUrl: './repo-info.component.html',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
})
export class RepoInfoComponent implements OnInit {
  @Input() public repoType: string;
  @Input() public activeRepository: RepoPermissionInfo;
  public renameForm: FormGroup;
  public descriptionForm: FormGroup;

  public loading = false;
  public private = false;

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly golangService: GolangService,
    private readonly cargoService: CargoService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.renameForm = new FormGroup({
      name: new FormControl('', [
        Validators.required,
        Validators.maxLength(25),
        Validators.pattern(/^[a-zA-Z0-9@_\-]+$/),
      ]),
    });

    this.descriptionForm = new FormGroup({
      description: new FormControl('', [Validators.maxLength(500)]),
    });
  }

  ngOnInit(): void {
    this.renameForm?.get('name').setValue(this.activeRepository.repoName);
    this.descriptionForm?.get('description').setValue(this.activeRepository.description);
  }

  public renameRepo() {
    const form: RepoNameForm = Object.assign(new RepoNameForm(), this.renameForm.value);

    this.dangerModalService.show('Rename Repository', 'Rename', () => {
      this.loading = true;
      this.renameForm.disable();

      this.renameRepoService(form)
        .then(() => {
          this.router.navigate([this.renameForm.get('name').value, 'settings']);

          this.toastService.show('Repository renamed successfully', 'success');
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.loading = false;
          this.renameForm.enable();
        });
    });
  }

  public updateRepoDescription() {
    const form: RepoDescriptionForm = Object.assign(new RepoDescriptionForm(), this.descriptionForm.value);

    this.loading = true;
    this.renameForm.disable();

    this.updateRepoDescriptionService(form)
      .then(() => {
        this.toastService.show('Repository description updated successfully', 'success');

        this.activeRepository.description = form.description;
        this.resetDescriptionForm();
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
        this.renameForm.enable();
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

  private renameRepoService(form: RepoNameForm) {
    switch (this.repoType) {
      case RepoType.MAVEN:
        return this.mavenService.updateRepositoryName(form);
      case RepoType.NPM:
        return this.npmService.updateRegistryName(form);
      case RepoType.PYPI:
        return this.pypiService.updateRepositoryName(form);
      case RepoType.DOCKER:
        return this.dockerService.updateRepositoryName(form);
      case RepoType.CARGO:
        return this.cargoService.updateRepositoryName(form);
      case RepoType.GOLANG:
        return this.golangService.updateRepositoryName(form);
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  private updateRepoDescriptionService(form: RepoDescriptionForm) {
    switch (this.repoType) {
      case RepoType.MAVEN:
        return this.mavenService.updateRepoDescription(form);
      case RepoType.NPM:
        return this.npmService.updateRegistryDescription(form);
      case RepoType.PYPI:
        return this.pypiService.updateRepoDescription(form);
      case RepoType.DOCKER:
        return this.dockerService.updateRepositoryDescription(form);
      case RepoType.CARGO:
        return this.cargoService.updateRepoDescription(form);
      case RepoType.GOLANG:
        return this.golangService.updateRepoDescription(form);
      default:
        return Promise.reject('Unsupported repository type');
    }
  }
}
