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

import { ProtocolRepoControllerService } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ToggleComponent } from '../../../../shared/components/toggle/toggle.component';
import { RepoSettingsForm } from '../../../../shared/dto/repo/repo-settings-form';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { MavenRepoSettingsForm } from '../../maven/dto/maven-repo-settings-form';

@Component({
  selector: 'app-package-override',
  standalone: true,
  templateUrl: './package-override.component.html',
  styleUrls: ['./package-override.component.css'],
  imports: [ReactiveFormsModule, ToggleComponent, RouterLink],
})
export class PackageOverrideComponent implements OnInit {
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Input() public parentForm: FormGroup;
  @Output() public fetch = new EventEmitter<void>();

  public allowOverride: boolean;

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly toastService: ToastService,
  ) {}

  ngOnInit(): void {
    this.allowOverride = this.parentForm.get('allowOverride')?.value;
  }

  public changeOverride() {
    let form: RepoSettingsForm | MavenRepoSettingsForm;

    if (this.repoType === RepoType.MAVEN) {
      const mavenForm = new MavenRepoSettingsForm();
      mavenForm.allowOverride = this.allowOverride;
      mavenForm.privateRepo = this.parentForm.get('privateRepository')?.value;
      mavenForm.snapshots = this.parentForm.get('snapshots')?.value;
      mavenForm.releases = this.parentForm.get('releases')?.value;
      form = mavenForm;
    } else {
      const generalForm = new RepoSettingsForm();
      generalForm.allowOverride = this.allowOverride;
      generalForm.privateRepo = this.parentForm.get('privateRepository')?.value;
      form = generalForm;
    }

    this.protocolRepoControllerService.updateSettings(this.repoName, form).subscribe({
      next: () => {
        this.parentForm.get('allowOverride')?.setValue(this.allowOverride);
        this.toastService.show(`Package override is now ${this.allowOverride ? 'allowed' : 'blocked'}`, 'success');
        this.fetch.emit();
      },
      error: () => {},
    });
  }
}
