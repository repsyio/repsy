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

import { TestBed } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { of, throwError } from 'rxjs';

import { CleanupPolicyItem, DockerCleanupPolicyApi } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { permission } from '../../testing/protocol-service-spec-helpers';
import { CleanupPolicyComponent } from './cleanup-policy.component';

const DISABLED: CleanupPolicyItem = {
  enabled: false,
  cadence: 'WEEKLY',
  keepLastN: 10,
  keepDays: 7,
  nameRegex: '.*',
};

describe('CleanupPolicyComponent', () => {
  let api: jasmine.SpyObj<DockerCleanupPolicyApi>;
  let toast: jasmine.SpyObj<ToastService>;
  let component: CleanupPolicyComponent;

  beforeEach(() => {
    api = jasmine.createSpyObj<DockerCleanupPolicyApi>('DockerCleanupPolicyApi', [
      'getDockerCleanupPolicy',
      'updateDockerCleanupPolicy',
      'updateDockerCleanupPolicyStatus',
    ]);
    api.getDockerCleanupPolicy.and.returnValue(of(DISABLED) as never);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    component = new CleanupPolicyComponent(TestBed.inject(FormBuilder), api, toast);
    component.activeRepository = permission('docker-repo', { canManage: true });
  });

  it('loads the policy of its repository, disabled by default', () => {
    component.ngOnInit();

    expect(api.getDockerCleanupPolicy).toHaveBeenCalledOnceWith('docker-repo');
    expect(component.enabled).toBeFalse();
    expect(component.loading).toBeFalse();
    expect(component.form.getRawValue().keepLastN).toBe(10);
  });

  it('enables the policy through the status route and shows the answer', () => {
    component.ngOnInit();
    api.updateDockerCleanupPolicyStatus.and.returnValue(of({ ...DISABLED, enabled: true }) as never);

    component.onToggleChange(true);

    expect(api.updateDockerCleanupPolicyStatus).toHaveBeenCalledOnceWith('docker-repo', { enabled: true });
    expect(component.enabled).toBeTrue();
    expect(toast.show).toHaveBeenCalledOnceWith('Cleanup policy enabled.', 'success');
  });

  it('does not save a disabled policy', () => {
    component.ngOnInit();

    component.save();

    expect(api.updateDockerCleanupPolicy).not.toHaveBeenCalled();
    expect(toast.show).toHaveBeenCalledOnceWith('Policy is disabled. Enable it before updating.', 'error');
  });

  it('saves the rules of an enabled policy', () => {
    api.getDockerCleanupPolicy.and.returnValue(of({ ...DISABLED, enabled: true }) as never);
    api.updateDockerCleanupPolicy.and.returnValue(of({ ...DISABLED, enabled: true, keepLastN: 5 }) as never);
    component.ngOnInit();
    component.editMode = true;
    component.form.patchValue({ keepLastN: 5, nameRegexKeep: 'keep-.*' });

    component.save();

    expect(api.updateDockerCleanupPolicy).toHaveBeenCalledOnceWith('docker-repo', {
      cadence: 'WEEKLY',
      keepLastN: 5,
      keepDays: 7,
      nameRegex: '.*',
      nameRegexKeep: 'keep-.*',
    });
    expect(component.editMode).toBeFalse();
  });

  it('keeps what the server has when the status change fails', () => {
    component.ngOnInit();
    api.updateDockerCleanupPolicyStatus.and.returnValue(throwError(() => new Error('boom')));

    component.onToggleChange(true);

    expect(component.enabled).toBeFalse();
    expect(component.submitting).toBeFalse();
  });
});
