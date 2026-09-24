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

import { fakeAsync, tick } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { config, of, Subject, throwError } from 'rxjs';

import { UserResponse } from '../../../../../../generated/api';
import { UserService } from '../../../../pages/user/service/user.service';
import { ToastService } from '../../toast/toast.service';
import { UserCreateModalComponent } from './user-create-modal.component';

const CREATED_USER: UserResponse = {
  id: 'user-1',
  username: 'bob',
  role: 'USER',
  createdAt: '2026-01-01T00:00:00Z',
  lastLoginAt: '2026-01-02T00:00:00Z',
};

describe('UserCreateModalComponent', () => {
  let component: UserCreateModalComponent;
  let userService: jasmine.SpyObj<UserService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let openChange: boolean[];
  let createdCount: number;

  beforeEach(() => {
    userService = jasmine.createSpyObj<UserService>('UserService', ['createUser']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    userService.createUser.and.returnValue(of(CREATED_USER));

    component = new UserCreateModalComponent(userService, new FormBuilder(), toastService);
    openChange = [];
    createdCount = 0;
    component.openChange.subscribe((open) => openChange.push(open));
    component.created.subscribe(() => createdCount++);
  });

  afterEach(() => {
    config.onUnhandledError = null;
  });

  function fill(values: Record<string, unknown> = {}): void {
    component.form.patchValue({
      username: 'bob',
      password: 'Passw0rd',
      confirmPassword: 'Passw0rd',
      ...values,
    });
  }

  it('starts as an empty, invalid form for a regular user', () => {
    expect(component.form.getRawValue()).toEqual({ username: '', password: '', confirmPassword: '', isAdmin: false });
    expect(component.form.invalid).toBeTrue();
    expect(component.loading).toBeFalse();
  });

  describe('username validation', () => {
    const isUsernameValid = (username: string): boolean => {
      fill({ username });
      return component.form.get('username').valid;
    };

    it('accepts 3 to 25 of a-z, 0-9, hyphen and underscore', () => {
      expect(isUsernameValid('bob')).toBeTrue();
      expect(isUsernameValid('bob_the-2nd')).toBeTrue();
      expect(isUsernameValid('a'.repeat(25))).toBeTrue();
    });

    it('rejects a blank, short, long or otherwise malformed username', () => {
      expect(isUsernameValid('')).toBeFalse();
      expect(component.form.get('username').hasError('required')).toBeTrue();
      expect(isUsernameValid('ab')).toBeFalse();
      expect(component.form.get('username').hasError('minlength')).toBeTrue();
      expect(isUsernameValid('a'.repeat(26))).toBeFalse();
      expect(component.form.get('username').hasError('maxlength')).toBeTrue();
      expect(isUsernameValid('Bob')).toBeFalse();
      expect(component.form.get('username').hasError('pattern')).toBeTrue();
      expect(isUsernameValid('bo b')).toBeFalse();
    });
  });

  describe('password validation', () => {
    const isPasswordValid = (password: string): boolean => {
      fill({ password, confirmPassword: password });
      return component.form.get('password').valid;
    };

    it('accepts 6 to 50 characters with a digit, a lower and an upper case letter and no whitespace', () => {
      expect(isPasswordValid('Passw0')).toBeTrue();
      expect(isPasswordValid('Aa1' + 'x'.repeat(47))).toBeTrue();
    });

    it('rejects a password that is too short, too long, weak or contains whitespace', () => {
      expect(isPasswordValid('Pa1xy')).toBeFalse();
      expect(component.form.get('password').hasError('minlength')).toBeTrue();
      expect(isPasswordValid('Aa1' + 'x'.repeat(48))).toBeFalse();
      expect(component.form.get('password').hasError('maxlength')).toBeTrue();
      expect(isPasswordValid('password1')).toBeFalse();
      expect(isPasswordValid('PASSWORD1')).toBeFalse();
      expect(isPasswordValid('Password')).toBeFalse();
      expect(component.form.get('password').hasError('pattern')).toBeTrue();
      expect(isPasswordValid('Pass w0rd')).toBeFalse();
    });

    it('requires a confirmation', () => {
      fill({ password: '', confirmPassword: '' });

      expect(component.form.get('confirmPassword').hasError('required')).toBeTrue();
      expect(component.form.invalid).toBeTrue();
    });
  });

  describe('password confirmation', () => {
    it('flags a mismatch on the group and on the confirmation field', () => {
      fill({ confirmPassword: 'Passw0rd2' });

      expect(component.form.errors).toEqual({ passwordMismatch: true });
      expect(component.form.get('confirmPassword').hasError('passwordMismatch')).toBeTrue();
      expect(component.form.invalid).toBeTrue();
    });

    it('reports an empty confirmation as required, not as a mismatch, once a password is typed', () => {
      fill({ confirmPassword: '' });

      const confirmation = component.form.get('confirmPassword');
      expect(confirmation.hasError('required')).toBeTrue();
      expect(confirmation.hasError('passwordMismatch')).toBeFalse();
      expect(component.form.errors).toBeNull();
      expect(component.form.invalid).toBeTrue();
    });

    it('goes from a mismatch back to required when the confirmation is emptied', () => {
      fill({ confirmPassword: 'Passw0rd2' });
      expect(component.form.get('confirmPassword').hasError('passwordMismatch')).toBeTrue();

      component.form.patchValue({ confirmPassword: '' });

      expect(component.form.get('confirmPassword').hasError('required')).toBeTrue();
      expect(component.form.get('confirmPassword').hasError('passwordMismatch')).toBeFalse();
    });

    it('clears the mismatch once the confirmation is corrected', () => {
      fill({ confirmPassword: 'Passw0rd2' });
      component.form.patchValue({ confirmPassword: 'Passw0rd' });

      expect(component.form.errors).toBeNull();
      expect(component.form.get('confirmPassword').errors).toBeNull();
      expect(component.form.valid).toBeTrue();
    });

    it('clears the mismatch when the password is changed to match the confirmation', () => {
      fill({ password: 'Passw0rd2' });
      expect(component.form.get('confirmPassword').hasError('passwordMismatch')).toBeTrue();

      component.form.patchValue({ password: 'Passw0rd' });

      expect(component.form.get('confirmPassword').hasError('passwordMismatch')).toBeFalse();
      expect(component.form.valid).toBeTrue();
    });
  });

  describe('createUser', () => {
    it('does nothing while the form is invalid', () => {
      fill({ confirmPassword: 'other' });

      component.createUser();

      expect(userService.createUser).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });

    it('creates a regular user by default', () => {
      fill();

      component.createUser();

      expect(userService.createUser).toHaveBeenCalledOnceWith({ username: 'bob', password: 'Passw0rd', role: 'USER' });
    });

    it('creates an administrator when the admin toggle is on', () => {
      fill({ isAdmin: true });

      component.createUser();

      expect(userService.createUser).toHaveBeenCalledOnceWith({ username: 'bob', password: 'Passw0rd', role: 'ADMIN' });
    });

    it('locks the form while the request runs', () => {
      const response = new Subject<UserResponse>();
      userService.createUser.and.returnValue(response);
      fill();

      component.createUser();

      expect(component.loading).toBeTrue();
      expect(component.form.disabled).toBeTrue();

      response.next(CREATED_USER);
      response.complete();

      expect(component.loading).toBeFalse();
      expect(component.form.enabled).toBeTrue();
    });

    it('toasts, closes the modal and tells the parent on success', () => {
      component.showPassword = true;
      component.showConfirmPassword = true;
      fill({ isAdmin: true });

      component.createUser();

      expect(toastService.show).toHaveBeenCalledOnceWith('User created successfully.', 'success');
      expect(openChange).toEqual([false]);
      expect(createdCount).toBe(1);
      expect(component.form.getRawValue()).toEqual({
        username: null,
        password: null,
        confirmPassword: null,
        isAdmin: false,
      });
      expect(component.showPassword).toBeFalse();
      expect(component.showConfirmPassword).toBeFalse();
    });

    it('stays open with the form unlocked and its values kept when the request fails', fakeAsync(() => {
      // The subscription has no error handler, so rxjs reports the failure asynchronously; the HTTP interceptor has
      // already shown the toast by then.
      const unhandled: unknown[] = [];
      config.onUnhandledError = (error) => unhandled.push(error);
      userService.createUser.and.returnValue(throwError(() => new Error('Username is taken')));
      fill();

      component.createUser();
      tick();

      expect(unhandled).toEqual([new Error('Username is taken')]);
      expect(toastService.show).not.toHaveBeenCalled();
      expect(openChange).toEqual([]);
      expect(createdCount).toBe(0);
      expect(component.form.enabled).toBeTrue();
      expect(component.loading).toBeFalse();
      expect(component.form.get('username').value).toBe('bob');
    }));
  });

  describe('visibility toggles', () => {
    it('toggle the password and the confirmation independently', () => {
      component.togglePasswordVisibility();

      expect(component.showPassword).toBeTrue();
      expect(component.showConfirmPassword).toBeFalse();

      component.toggleConfirmPasswordVisibility();
      component.togglePasswordVisibility();

      expect(component.showPassword).toBeFalse();
      expect(component.showConfirmPassword).toBeTrue();
    });
  });

  describe('closeModal', () => {
    it('clears the form, hides the passwords and tells the parent to close', () => {
      component.showPassword = true;
      fill({ isAdmin: true });

      component.closeModal();

      expect(component.form.getRawValue()).toEqual({
        username: null,
        password: null,
        confirmPassword: null,
        isAdmin: false,
      });
      expect(component.showPassword).toBeFalse();
      expect(openChange).toEqual([false]);
    });
  });
});
