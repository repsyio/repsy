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

import { Router } from '@angular/router';

import { ProtocolRepoControllerService } from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoInfoComponent } from './repo-info.component';

describe('RepoInfoComponent rename validation', () => {
  let component: RepoInfoComponent;

  beforeEach(() => {
    component = new RepoInfoComponent(
      {} as ProtocolRepoControllerService,
      {} as ToastService,
      {} as DangerModalService,
      {} as Router,
    );
  });

  const isValid = (name: string): boolean => {
    component.renameForm.get('name').setValue(name);
    return component.renameForm.get('name').valid;
  };

  it('accepts letters, digits, underscore and inner hyphens', () => {
    expect(isValid('my-repo_1')).toBeTrue();
    expect(isValid('_private')).toBeTrue();
  });

  it('rejects a name that starts with a hyphen', () => {
    expect(isValid('-repo')).toBeFalse();
    expect(component.renameForm.get('name').errors?.['pattern']).toBeTruthy();
  });

  it('rejects characters the API does not allow', () => {
    expect(isValid('my@repo')).toBeFalse();
    expect(isValid('my repo')).toBeFalse();
  });
});
