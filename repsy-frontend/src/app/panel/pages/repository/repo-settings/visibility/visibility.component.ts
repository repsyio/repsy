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

import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ToggleComponent } from '../../../../shared/components/toggle/toggle.component';
import { RepoSettingsForm } from '../../../../shared/dto/repo/repo-settings-form';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { DockerService } from '../../docker/service/docker.service';
import { MavenRepoSettingsForm } from '../../maven/dto/maven-repo-settings-form';
import { MavenService } from '../../maven/service/maven.service';
import { NpmService } from '../../npm/service/npm.service';
import { PypiService } from '../../pypi/service/pypi.service';

@Component({
  selector: 'app-visibility',
  templateUrl: './visibility.component.html',
  styleUrls: ['./visibility.component.css'],
  standalone: true,
  imports: [ReactiveFormsModule, ToggleComponent, RouterLink],
})
export class VisibilityComponent {
  @Input() public repoType: string;
  @Input() public parentForm: FormGroup;
  @Output() public fetch = new EventEmitter<void>();

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly toastService: ToastService,
  ) {}

  public changePrivacy(isPublic: boolean) {
    this.parentForm.get('privateRepository').setValue(!isPublic);

    const privacy = this.parentForm.get('privateRepository').value;

    let form;

    if (this.repoType === RepoType.MAVEN) {
      form = new MavenRepoSettingsForm();

      (form as MavenRepoSettingsForm) = Object.assign(new MavenRepoSettingsForm(), this.parentForm.value);
      form.privateRepo = this.parentForm.get('privateRepository').value;
      form.allowOverride = this.parentForm.get('allowOverride').value;
    } else {
      form = new RepoSettingsForm();
      form.privateRepo = this.parentForm.get('privateRepository').value;
      form.allowOverride = this.parentForm.get('allowOverride').value;
    }

    this.changePrivacyService(form)
      .then(() => {
        this.fetch.emit();
        this.toastService.show(`Repository visibility has changed as ${privacy ? 'private' : 'public'}`, 'success');
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      });
  }

  private changePrivacyService(form: RepoSettingsForm) {
    switch (this.repoType) {
      case RepoType.MAVEN:
        return this.mavenService.updateRepoSettings(form);
      case RepoType.NPM:
        return this.npmService.updateRepoSettings(form);
      case RepoType.PYPI:
        return this.pypiService.updateRepoSettings(form);
      case RepoType.DOCKER:
        return this.dockerService.updateRepoSettings(form);
      default:
        return Promise.reject('Unsupported repository type');
    }
  }
}
