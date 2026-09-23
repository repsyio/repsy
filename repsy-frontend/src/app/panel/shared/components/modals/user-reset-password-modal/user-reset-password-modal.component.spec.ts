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

import { fakeAsync, flushMicrotasks } from '@angular/core/testing';

import { ToastService } from '../../toast/toast.service';
import { UserResetPasswordModalComponent } from './user-reset-password-modal.component';

describe('UserResetPasswordModalComponent', () => {
  let component: UserResetPasswordModalComponent;
  let toastService: jasmine.SpyObj<ToastService>;
  let writeText: jasmine.Spy;

  beforeEach(() => {
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    writeText = spyOn(navigator.clipboard, 'writeText').and.resolveTo();
    component = new UserResetPasswordModalComponent(toastService);
  });

  it('hides the new password until it is asked for, and toggles it', () => {
    expect(component.showPassword).toBeFalse();

    component.togglePasswordVisibility();
    expect(component.showPassword).toBeTrue();

    component.togglePasswordVisibility();
    expect(component.showPassword).toBeFalse();
  });

  it('closes by telling the parent, and hides the password again for the next time', () => {
    const emitted: boolean[] = [];
    component.openChange.subscribe((value) => emitted.push(value));
    component.togglePasswordVisibility();

    component.closeModal();

    expect(emitted).toEqual([false]);
    expect(component.showPassword).toBeFalse();
  });

  it('copies the text to the clipboard and only then confirms with a toast', fakeAsync(() => {
    component.copyToClipboard('s3cret');

    expect(writeText).toHaveBeenCalledOnceWith('s3cret');
    expect(toastService.show).not.toHaveBeenCalled();

    flushMicrotasks();

    expect(toastService.show).toHaveBeenCalledOnceWith('Copied to clipboard', 'success');
  }));
});
