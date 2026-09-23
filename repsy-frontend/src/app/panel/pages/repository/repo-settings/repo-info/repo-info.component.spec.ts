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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';

import { ProtocolRepoControllerService, RepoPermissionInfo } from '../../../../../../generated/api';
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

  // RPS-1158: reserved names collide with the panel's fixed top-level routes.
  it('rejects a name reserved by the panel routes, case-insensitively', () => {
    expect(isValid('security')).toBeFalse();
    expect(component.renameForm.get('name').errors?.['reservedName']).toBeTruthy();

    expect(isValid('Users')).toBeFalse();
    expect(component.renameForm.get('name').errors?.['reservedName']).toBeTruthy();
  });

  it('accepts a name that is not reserved', () => {
    expect(isValid('user-service')).toBeTrue();
  });
});

// RPS-1103: the template showed the maxlength message for a 'pattern' error and the pattern
// message for a 'maxlength' error. Rendering the real template (not just reading the FormControl's
// error keys, which were never swapped) is what would have caught the bug.
describe('RepoInfoComponent rename form error messages', () => {
  let fixture: ComponentFixture<RepoInfoComponent>;
  let element: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RepoInfoComponent],
      providers: [
        { provide: ProtocolRepoControllerService, useValue: {} },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({}) } },
        },
      ],
    });

    fixture = TestBed.createComponent(RepoInfoComponent);
    const activeRepository: RepoPermissionInfo = {
      repoName: 'my-repo',
      canRead: true,
      canWrite: true,
      canManage: true,
      private: false,
    };
    fixture.componentInstance.activeRepository = activeRepository;
    fixture.componentInstance.repoType = 'MAVEN';
    fixture.detectChanges();
    element = fixture.nativeElement as HTMLElement;
  });

  function setNameAndTouch(value: string): void {
    const control = fixture.componentInstance.renameForm.get('name');
    control.setValue(value);
    control.markAsTouched();
    fixture.detectChanges();
  }

  it('shows the maxlength message, not the pattern one, for a name over 25 characters', () => {
    setNameAndTouch('a'.repeat(26));

    expect(element.textContent).toContain('Should be maximum 25 characters');
    expect(element.textContent).not.toContain('Should contain only letters, numbers');
  });

  it('shows the pattern message, not the maxlength one, for a name starting with a hyphen', () => {
    setNameAndTouch('-repo');

    expect(element.textContent).toContain('Should contain only letters, numbers, - and _, and not start with -');
    expect(element.textContent).not.toContain('Should be maximum 25 characters');
  });

  it('shows the reserved-name message for a name reserved by the panel routes', () => {
    setNameAndTouch('security');

    expect(element.textContent).toContain('This name is reserved for the panel');
  });
});
