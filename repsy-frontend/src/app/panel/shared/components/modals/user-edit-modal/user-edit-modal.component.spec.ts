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

import { SimpleChange, SimpleChanges } from '@angular/core';
import { fakeAsync, tick } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { config, of, Subject, throwError } from 'rxjs';

import { UserResponse } from '../../../../../../generated/api';
import { UserService } from '../../../../pages/user/service/user.service';
import { ToastService } from '../../toast/toast.service';
import { UserEditModalComponent } from './user-edit-modal.component';

const ADMIN: UserResponse = {
  id: 'user-1',
  username: 'alice',
  role: 'ADMIN',
  createdAt: '2026-01-01T00:00:00Z',
  lastLoginAt: '2026-01-02T00:00:00Z',
};
const REGULAR: UserResponse = { ...ADMIN, id: 'user-2', username: 'bob', role: 'USER' };

describe('UserEditModalComponent', () => {
  let component: UserEditModalComponent;
  let userService: jasmine.SpyObj<UserService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let openChange: boolean[];
  let updatedCount: number;

  beforeEach(() => {
    userService = jasmine.createSpyObj<UserService>('UserService', ['updateUser']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    userService.updateUser.and.returnValue(of(REGULAR));

    component = new UserEditModalComponent(new FormBuilder(), userService, toastService);
    openChange = [];
    updatedCount = 0;
    component.openChange.subscribe((open) => openChange.push(open));
    component.updated.subscribe(() => updatedCount++);
  });

  afterEach(() => {
    config.onUnhandledError = null;
  });

  /** Opens the modal for `user` the way the parent's input bindings do. */
  function open(user: UserResponse, isLastAdmin = false): void {
    component.user = user;
    component.open = true;
    component.isLastAdmin = isLastAdmin;
    component.ngOnChanges({ user: new SimpleChange(null, user, true), open: new SimpleChange(false, true, true) });
  }

  describe('ngOnChanges', () => {
    it('fills the form from the user when the modal opens', () => {
      open(ADMIN);

      expect(component.form.getRawValue()).toEqual({ username: 'alice', isAdmin: true });

      open(REGULAR);

      expect(component.form.getRawValue()).toEqual({ username: 'bob', isAdmin: false });
    });

    it('lets the role be changed unless the user is the last administrator', () => {
      open(ADMIN);
      expect(component.form.get('isAdmin').enabled).toBeTrue();

      open(ADMIN, true);
      expect(component.form.get('isAdmin').disabled).toBeTrue();

      open(REGULAR, false);
      expect(component.form.get('isAdmin').enabled).toBeTrue();
    });

    it('ignores changes while the modal is closed or has no user', () => {
      component.user = ADMIN;
      component.open = false;
      component.ngOnChanges({ user: new SimpleChange(null, ADMIN, true) });
      expect(component.form.get('username').value).toBe('');

      component.user = undefined;
      component.open = true;
      component.ngOnChanges({ open: new SimpleChange(false, true, false) });
      expect(component.form.get('username').value).toBe('');
    });

    it('ignores changes that concern neither the user nor the open flag', () => {
      component.user = ADMIN;
      component.open = true;
      const changes: SimpleChanges = { isLastAdmin: new SimpleChange(false, true, false) };

      component.ngOnChanges(changes);

      expect(component.form.get('username').value).toBe('');
    });
  });

  describe('validation', () => {
    const isUsernameValid = (username: string): boolean => {
      open(REGULAR);
      component.form.patchValue({ username });
      return component.form.valid;
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
    });
  });

  describe('updateUser', () => {
    it('does nothing while the form is invalid', () => {
      open(REGULAR);
      component.form.patchValue({ username: 'x' });

      component.updateUser();

      expect(userService.updateUser).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });

    it('sends the edited username and role for the user id', () => {
      open(REGULAR);
      component.form.patchValue({ username: 'robert', isAdmin: true });

      component.updateUser();

      expect(userService.updateUser).toHaveBeenCalledOnceWith('user-2', { username: 'robert', role: 'ADMIN' });
    });

    it('demotes to a regular user when the admin toggle is turned off', () => {
      open(ADMIN);
      component.form.patchValue({ isAdmin: false });

      component.updateUser();

      expect(userService.updateUser).toHaveBeenCalledOnceWith('user-1', { username: 'alice', role: 'USER' });
    });

    it('refuses to remove the admin role from the last administrator', () => {
      open(ADMIN, true);
      component.form.get('isAdmin').setValue(false);

      component.updateUser();

      expect(toastService.show).toHaveBeenCalledOnceWith(
        'Cannot remove admin role from the last admin. Create another admin first.',
        'error',
      );
      expect(userService.updateUser).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });

    it('still saves the last administrator, keeping the admin role of the disabled toggle', () => {
      open(ADMIN, true);
      component.form.patchValue({ username: 'alice2' });

      component.updateUser();

      expect(userService.updateUser).toHaveBeenCalledOnceWith('user-1', { username: 'alice2', role: 'ADMIN' });
    });

    it('locks the form while the request runs', () => {
      const response = new Subject<UserResponse>();
      userService.updateUser.and.returnValue(response);
      open(REGULAR);

      component.updateUser();

      expect(component.loading).toBeTrue();
      expect(component.form.disabled).toBeTrue();

      response.next(REGULAR);
      response.complete();

      expect(component.loading).toBeFalse();
      expect(component.form.enabled).toBeTrue();
    });

    it('toasts, tells the parent and closes the modal on success', () => {
      open(REGULAR);

      component.updateUser();

      expect(toastService.show).toHaveBeenCalledOnceWith('User updated successfully', 'success');
      expect(updatedCount).toBe(1);
      expect(openChange).toEqual([false]);
      expect(component.form.value).toEqual({ username: null, isAdmin: null });
    });

    it('keeps the admin toggle disabled after saving the last administrator', () => {
      open(ADMIN, true);

      component.updateUser();

      expect(component.form.get('username').enabled).toBeTrue();
      expect(component.form.get('isAdmin').disabled).toBeTrue();
    });

    it('stays open with the form unlocked when the request fails', fakeAsync(() => {
      // The subscription has no error handler, so rxjs reports the failure asynchronously; the HTTP interceptor has
      // already shown the toast by then.
      const unhandled: unknown[] = [];
      config.onUnhandledError = (error) => unhandled.push(error);
      userService.updateUser.and.returnValue(throwError(() => new Error('Username is taken')));
      open(REGULAR);

      component.updateUser();
      tick();

      expect(unhandled).toEqual([new Error('Username is taken')]);
      expect(toastService.show).not.toHaveBeenCalled();
      expect(updatedCount).toBe(0);
      expect(openChange).toEqual([]);
      expect(component.form.enabled).toBeTrue();
      expect(component.loading).toBeFalse();
      expect(component.form.get('username').value).toBe('bob');
    }));
  });

  describe('closeModal', () => {
    it('clears the form, re-enables the role toggle and tells the parent to close', () => {
      open(ADMIN, true);

      component.closeModal();

      expect(component.form.get('isAdmin').enabled).toBeTrue();
      expect(component.form.get('username').value).toBeNull();
      expect(openChange).toEqual([false]);
    });
  });
});
