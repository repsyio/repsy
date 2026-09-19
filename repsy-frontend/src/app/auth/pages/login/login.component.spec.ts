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

import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder } from '@angular/forms';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { ToastService } from '../../../panel/shared/components/toast/toast.service';
import { AuthService } from '../service/auth.service';
import { LoginComponent } from './login.component';

const INVALID_CREDENTIALS_TEXT = 'Username or password is incorrect.';

describe('LoginComponent', () => {
  let component: LoginComponent;
  let authService: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;
  let toastService: jasmine.SpyObj<ToastService>;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['logIn']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);

    component = new LoginComponent(router, new FormBuilder(), authService, toastService);
    component.ngOnInit();
    component.form.setValue({ username: 'someone', password: 'Passw0rd' });
  });

  function failLogin(status: number, error: unknown): void {
    authService.logIn.and.returnValue(throwError(() => new HttpErrorResponse({ status, error })));
    component.login();
  }

  it('navigates home after a successful login', () => {
    authService.logIn.and.returnValue(of(undefined));

    component.login();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
    expect(toastService.show).not.toHaveBeenCalled();
  });

  it('shows the invalidCredentials text on a 401, which errorHandlerInterceptor leaves to the caller', () => {
    failLogin(401, { msgId: 'invalidCredentials', text: INVALID_CREDENTIALS_TEXT });

    expect(toastService.show).toHaveBeenCalledOnceWith(INVALID_CREDENTIALS_TEXT, 'error');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(component.loading).toBeFalse();
    expect(component.form.enabled).toBeTrue();
  });

  it('falls back to a default message on a 401 without a body', () => {
    failLogin(401, null);

    expect(toastService.show).toHaveBeenCalledOnceWith(INVALID_CREDENTIALS_TEXT, 'error');
  });

  it('leaves other errors to errorHandlerInterceptor, which already shows them', () => {
    failLogin(500, { text: 'Server error' });

    expect(toastService.show).not.toHaveBeenCalled();
    expect(component.loading).toBeFalse();
    expect(component.form.enabled).toBeTrue();
  });
});
