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

import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';

import { AuthService } from '../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { ProfileService } from '../service/profile.service';
import { DeleteAccountComponent } from './delete-account.component';

describe('DeleteAccountComponent', () => {
  let component: DeleteAccountComponent;
  let router: jasmine.SpyObj<Router>;
  let profileService: jasmine.SpyObj<ProfileService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let authService: jasmine.SpyObj<AuthService>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    profileService = jasmine.createSpyObj<ProfileService>('ProfileService', ['deleteAccount']);
    profileService.deleteAccount.and.returnValue(of(undefined));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['logOut']);
    dangerModalService = new DangerModalService();
    component = new DeleteAccountComponent(router, profileService, toastService, authService, dangerModalService);
  });

  it('asks for confirmation before deleting the account', () => {
    component.confirmAccountDelete();

    expect(dangerModalService.modal).toEqual({ title: 'Delete Account', action: 'Delete', message: null });
    expect(profileService.deleteAccount).not.toHaveBeenCalled();
    expect(component.loading).toBeFalse();
  });

  it('deletes the account once confirmed, then toasts, logs out and goes to the login page', () => {
    component.confirmAccountDelete();

    dangerModalService.call();

    expect(profileService.deleteAccount).toHaveBeenCalledTimes(1);
    expect(toastService.show).toHaveBeenCalledOnceWith('Account deleted successfully.', 'success');
    expect(authService.logOut).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login');
    expect(component.loading).toBeFalse();
  });

  it('shows the account as being deleted until the request answers', () => {
    const answer = new Subject<void>();
    profileService.deleteAccount.and.returnValue(answer);
    component.confirmAccountDelete();

    dangerModalService.call();

    expect(component.loading).toBeTrue();

    answer.next();
    answer.complete();

    expect(component.loading).toBeFalse();
  });

  it('leaves the toast to the error interceptor, and keeps the user signed in, when the deletion fails', () => {
    profileService.deleteAccount.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { text: 'Cannot delete the last admin' } })),
    );
    component.confirmAccountDelete();

    dangerModalService.call();

    expect(toastService.show).not.toHaveBeenCalled();
    expect(authService.logOut).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(component.loading).toBeFalse();
  });
});
