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

import { DockerService } from '../../../../pages/repository/docker/service/docker.service';
import { GolangService } from '../../../../pages/repository/golang/service/golang.service';
import { MavenService } from '../../../../pages/repository/maven/service/maven.service';
import { NpmService } from '../../../../pages/repository/npm/service/npm.service';
import { PypiService } from '../../../../pages/repository/pypi/service/pypi.service';
import { RepoForm } from '../../../dto/repo/repo-form';
import { RepoType } from '../../../dto/repo/repo-type';
import { SelectorComponent } from '../../selector/selector.component';
import { ToastService } from '../../toast/toast.service';
import { ToggleComponent } from '../../toggle/toggle.component';

@Component({
  selector: 'app-repository-modal',
  standalone: true,
  imports: [SelectorComponent, ReactiveFormsModule, ToggleComponent],
  templateUrl: './repository-create-modal.component.html',
  styleUrl: './repository-create-modal.component.css',
})
export class RepositoryCreateModalComponent implements OnInit {
  @Output() openChange = new EventEmitter<boolean>();
  @Output() created = new EventEmitter<void>();
  @Input() public open: boolean;
  @Input() selectedOption: RepoType;

  public options = [RepoType.DOCKER, RepoType.GOLANG, RepoType.MAVEN, RepoType.NPM, RepoType.PYPI];
  public form: FormGroup;

  public loading = false;

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly golangService: GolangService,
    private readonly fb: FormBuilder,
    private readonly router: Router,
    private readonly toastService: ToastService,
  ) {}

  public ngOnInit(): void {
    if (this.selectedOption == null) {
      this.selectedOption = RepoType.DOCKER;
    }

    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.maxLength(25), Validators.pattern(/^[a-zA-Z0-9_\-]+$/)]],
      privateRepo: [true],
      description: ['', [Validators.maxLength(500)]],
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

    const form: RepoForm = Object.assign(new RepoForm(), this.form.value);

    this.createRepositoryService(form)
      .then(() => {
        this.router.navigate(['/repositories']).then(() => {
          this.toastService.show('Repository created successfully', 'success');
        });

        this.created.emit();
        this.closeModal();
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.form.enable();
        this.loading = false;
      });
  }

  private createRepositoryService(form: RepoForm) {
    switch (this.selectedOption) {
      case RepoType.MAVEN:
        return this.mavenService.createRepository(form);
      case RepoType.NPM:
        return this.npmService.createRegistry(form);
      case RepoType.PYPI:
        return this.pypiService.createRepository(form);
      case RepoType.DOCKER:
        return this.dockerService.createRepository(form);
      case RepoType.GOLANG:
        return this.golangService.createRepository(form);
      default:
        return Promise.reject('Unsupported repository type');
    }
  }
}
