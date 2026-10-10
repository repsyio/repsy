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

import { ReposApi, RepoSettingsForm } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ToggleComponent } from '../../../../shared/components/toggle/toggle.component';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { saveRepoSetting } from '../save-repo-setting';

@Component({
  selector: 'app-package-override',
  standalone: true,
  templateUrl: './package-override.component.html',
  styleUrls: ['./package-override.component.css'],
  imports: [ReactiveFormsModule, ToggleComponent, RouterLink],
})
export class PackageOverrideComponent implements OnInit {
  @Input() repoType: string;
  @Input() repoName: string;
  @Input() parentForm: FormGroup;
  @Output() fetch = new EventEmitter<void>();

  allowOverride: boolean;

  /** A save is on its way: the toggle is locked, so a double click sends one request (RPS-1618). */
  saving = false;

  constructor(
    private readonly reposApi: ReposApi,
    private readonly toastService: ToastService,
  ) {}

  /** The Maven rule has an exception the shared text does not tell: a SNAPSHOT can always be deployed again. */
  get isMaven(): boolean {
    return this.repoType === RepoType.MAVEN;
  }

  ngOnInit(): void {
    this.allowOverride = this.parentForm.get('allowOverride')?.value;
  }

  changeOverride() {
    const allowOverride = this.allowOverride;

    this.saving = true;

    // Only the field this toggle owns is sent (RPS-1619): the rest of the form was loaded when the page opened.
    const form: RepoSettingsForm = { allowOverride };

    saveRepoSetting(this.reposApi.updateRepoSettings(this.repoName, form), {
      saved: () => {
        this.parentForm.get('allowOverride')?.setValue(allowOverride);
        this.toastService.show(`Package override is now ${allowOverride ? 'allowed' : 'blocked'}`, 'success');
        this.fetch.emit();
      },
      failed: () => (this.allowOverride = !allowOverride),
      settled: () => (this.saving = false),
    });
  }
}
