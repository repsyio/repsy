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

import { ProtocolRepoControllerService } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ToggleComponent } from '../../../../shared/components/toggle/toggle.component';
import { RepoSettingsForm } from '../../../../shared/dto/repo/repo-settings-form';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { MavenRepoSettingsForm } from '../../maven/dto/maven-repo-settings-form';

@Component({
  selector: 'app-visibility',
  templateUrl: './visibility.component.html',
  styleUrls: ['./visibility.component.css'],
  standalone: true,
  imports: [ReactiveFormsModule, ToggleComponent, RouterLink],
})
export class VisibilityComponent {
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Input() public parentForm: FormGroup;
  @Output() public fetch = new EventEmitter<void>();

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly toastService: ToastService,
  ) {}

  public changePrivacy(isPublic: boolean) {
    this.parentForm.get('privateRepository').setValue(!isPublic);

    const privacy = this.parentForm.get('privateRepository').value;

    let form: RepoSettingsForm | MavenRepoSettingsForm;

    if (this.repoType === RepoType.MAVEN) {
      form = Object.assign(new MavenRepoSettingsForm(), this.parentForm.value);
      (form as MavenRepoSettingsForm).privateRepo = this.parentForm.get('privateRepository').value;
      (form as MavenRepoSettingsForm).allowOverride = this.parentForm.get('allowOverride').value;
      (form as MavenRepoSettingsForm).securityScanEnabled = this.parentForm.get('securityScanEnabled').value;
    } else if (this.repoType === RepoType.NUGET) {
      form = new RepoSettingsForm();
      form.privateRepo = this.parentForm.get('privateRepository').value;
      form.allowOverride = this.parentForm.get('allowOverride').value;
      form.releases = this.parentForm.get('releases').value;
      form.snapshots = this.parentForm.get('snapshots').value;
      form.securityScanEnabled = this.parentForm.get('securityScanEnabled').value;
    } else {
      form = new RepoSettingsForm();
      form.privateRepo = this.parentForm.get('privateRepository').value;
      form.allowOverride = this.parentForm.get('allowOverride').value;
      form.securityScanEnabled = this.parentForm.get('securityScanEnabled').value;
    }

    this.protocolRepoControllerService.updateSettings(this.repoName, form).subscribe({
      next: () => {
        this.fetch.emit();
        this.toastService.show(`Repository visibility has changed as ${privacy ? 'private' : 'public'}`, 'success');
      },
      error: () => {},
    });
  }
}
