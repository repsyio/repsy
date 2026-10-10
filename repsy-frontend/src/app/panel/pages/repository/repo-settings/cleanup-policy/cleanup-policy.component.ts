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
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';

import {
  CleanupPolicyForm,
  CleanupPolicyItem,
  DockerCleanupPolicyApi,
  RepoPermissionInfo,
} from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ToggleComponent } from '../../../../shared/components/toggle/toggle.component';

@Component({
  selector: 'app-cleanup-policy',
  templateUrl: './cleanup-policy.component.html',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, ToggleComponent],
})
export class CleanupPolicyComponent implements OnInit {
  @Input() activeRepository: RepoPermissionInfo;

  loading = true;
  submitting = false;
  editMode = false;
  policy?: CleanupPolicyItem;

  readonly cadenceOptions: { value: CleanupPolicyForm.CadenceEnum; label: string }[] = [
    { value: 'DAILY', label: 'Every day' },
    { value: 'WEEKLY', label: 'Every week' },
    { value: 'BIWEEKLY', label: 'Every two weeks' },
    { value: 'MONTHLY', label: 'Every month' },
    { value: 'QUARTERLY', label: 'Every quarter' },
  ];
  readonly keepNOptions = [1, 5, 10, 25, 50, 100];
  readonly olderThanOptions = [
    { value: 7, label: '7 days' },
    { value: 14, label: '14 days' },
    { value: 30, label: '30 days' },
    { value: 90, label: '90 days' },
  ];

  readonly form: ReturnType<CleanupPolicyComponent['buildForm']>;

  constructor(
    private readonly fb: FormBuilder,
    private readonly api: DockerCleanupPolicyApi,
    private readonly toastService: ToastService,
  ) {
    this.form = this.buildForm();
  }

  ngOnInit(): void {
    this.load();
  }

  get enabled(): boolean {
    return this.policy?.enabled === true;
  }

  get cadenceLabel(): string {
    return this.cadenceOptions.find((o) => o.value === this.policy?.cadence)?.label.toLowerCase() ?? '';
  }

  toggleEditMode(): void {
    this.editMode = !this.editMode;
    if (!this.editMode && this.policy) {
      this.patchForm(this.policy);
    }
  }

  onToggleChange(enabled: boolean): void {
    this.submitting = true;
    this.api
      .updateDockerCleanupPolicyStatus(this.activeRepository.repoName, { enabled })
      .pipe(finalize(() => (this.submitting = false)))
      .subscribe({
        next: (policy) => {
          this.setPolicy(policy);
          this.toastService.show(enabled ? 'Cleanup policy enabled.' : 'Cleanup policy disabled.', 'success');
        },
        // The error interceptor toasts; the toggle goes back to what the server has.
        error: () => this.setPolicy(this.policy),
      });
  }

  save(): void {
    if (!this.enabled) {
      this.toastService.show('Policy is disabled. Enable it before updating.', 'error');
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    const body: CleanupPolicyForm = {
      cadence: value.cadence,
      keepLastN: Number(value.keepLastN),
      keepDays: Number(value.keepDays),
      nameRegex: value.nameRegex,
      nameRegexKeep: value.nameRegexKeep || undefined,
    };

    this.submitting = true;
    this.api
      .updateDockerCleanupPolicy(this.activeRepository.repoName, body)
      .pipe(finalize(() => (this.submitting = false)))
      .subscribe({
        next: (policy) => {
          this.setPolicy(policy);
          this.editMode = false;
          this.toastService.show('Cleanup policy saved successfully!', 'success');
        },
        error: () => {},
      });
  }

  private buildForm() {
    return this.fb.group({
      cadence: this.fb.nonNullable.control<CleanupPolicyForm.CadenceEnum>('WEEKLY', [Validators.required]),
      keepLastN: this.fb.nonNullable.control(10, [Validators.required, Validators.min(1), Validators.max(100)]),
      keepDays: this.fb.nonNullable.control(7, [Validators.required, Validators.min(7), Validators.max(90)]),
      nameRegex: this.fb.nonNullable.control('.*', [Validators.required]),
      nameRegexKeep: this.fb.nonNullable.control(''),
    });
  }

  private load(): void {
    this.api
      .getDockerCleanupPolicy(this.activeRepository.repoName)
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (policy) => this.setPolicy(policy),
        error: () => {},
      });
  }

  private setPolicy(policy: CleanupPolicyItem | undefined): void {
    this.policy = policy;
    if (policy) {
      this.patchForm(policy);
    }
  }

  private patchForm(policy: CleanupPolicyItem): void {
    this.form.reset({
      cadence: policy.cadence,
      keepLastN: policy.keepLastN,
      keepDays: policy.keepDays,
      nameRegex: policy.nameRegex,
      nameRegexKeep: policy.nameRegexKeep ?? '',
    });
  }
}
