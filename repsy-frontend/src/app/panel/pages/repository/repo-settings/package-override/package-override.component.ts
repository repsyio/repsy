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
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ToggleComponent } from '../../../../shared/components/toggle/toggle.component';
import { RepoSettingsForm } from '../../../../shared/dto/repo/repo-settings-form';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { CargoService } from '../../cargo/service/cargo.service';
import { DockerService } from '../../docker/service/docker.service';
import { MavenRepoSettingsForm } from '../../maven/dto/maven-repo-settings-form';
import { MavenService } from '../../maven/service/maven.service';
import { NpmService } from '../../npm/service/npm.service';
import { PypiService } from '../../pypi/service/pypi.service';

@Component({
  selector: 'app-package-override',
  standalone: true,
  templateUrl: './package-override.component.html',
  styleUrls: ['./package-override.component.css'],
  imports: [ReactiveFormsModule, ToggleComponent, RouterLink],
})
export class PackageOverrideComponent implements OnInit {
  @Input() public repoType: string;
  @Input() public parentForm: FormGroup;
  @Output() public fetch = new EventEmitter<void>();

  public allowOverride: boolean;

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly cargoService: CargoService,
    private readonly toastService: ToastService,
  ) {}

  ngOnInit(): void {
    this.allowOverride = this.parentForm.get('allowOverride')?.value;
  }

  public changeOverride() {
    if (this.repoType === RepoType.MAVEN) {
      const form = new MavenRepoSettingsForm();
      form.allowOverride = this.allowOverride;
      form.privateRepo = this.parentForm.get('privateRepository')?.value;
      form.snapshots = this.parentForm.get('snapshots')?.value;
      form.releases = this.parentForm.get('releases')?.value;

      this.mavenService
        .updateRepoSettings(form)
        .then(() => {
          this.parentForm.get('allowOverride')?.setValue(this.allowOverride);
          this.toastService.show(`Package override is now ${this.allowOverride ? 'allowed' : 'blocked'}`, 'success');
          this.fetch.emit();
        })
        .catch((err: string) => this.toastService.show(err, 'error'));
    } else if (this.repoType === RepoType.NPM || this.repoType === RepoType.PYPI || this.repoType === RepoType.DOCKER) {
      const form = new RepoSettingsForm();
      form.allowOverride = this.allowOverride;
      form.privateRepo = this.parentForm.get('privateRepository')?.value;

      this.updateRepoSettingsService(form)
        .then(() => {
          this.parentForm.get('allowOverride')?.setValue(this.allowOverride);
          this.toastService.show(`Package override is now ${this.allowOverride ? 'allowed' : 'blocked'}`, 'success');
          this.fetch.emit();
        })
        .catch((err: string) => this.toastService.show(err, 'error'));
    }
  }

  private updateRepoSettingsService(form: MavenRepoSettingsForm | RepoSettingsForm) {
    switch (this.repoType) {
      case RepoType.NPM:
        return this.npmService.updateRepoSettings(form);
      case RepoType.PYPI:
        return this.pypiService.updateRepoSettings(form);
      case RepoType.DOCKER:
        return this.dockerService.updateRepoSettings(form);
      case RepoType.CARGO:
        return this.cargoService.updateRepoSettings(form);
      default:
        return Promise.reject('Unsupported repository type');
    }
  }
}
