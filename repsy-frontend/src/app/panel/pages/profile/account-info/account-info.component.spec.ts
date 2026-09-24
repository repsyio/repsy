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

import { FormBuilder } from '@angular/forms';
import { of, Subject, throwError } from 'rxjs';

import { LoginInfo } from '../../../../../generated/api';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { renderComponent } from '../../repository/testing/render-spec-helpers';
import { ProfileService } from '../service/profile.service';
import { AccountInfoComponent } from './account-info.component';

const LOGIN_INFO: LoginInfo = { username: 'alice', token: 'access', refreshToken: 'refresh' };

describe('AccountInfoComponent', () => {
  let component: AccountInfoComponent;
  let profileService: jasmine.SpyObj<ProfileService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    profileService = jasmine.createSpyObj<ProfileService>('ProfileService', ['updatePassword', 'updateUsername']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();

    component = new AccountInfoComponent(new FormBuilder(), profileService, toastService, dangerModalService);
    component.username = 'alice';
    component.ngOnInit();
  });

  function setPasswords(newPassword: string, passwordConfirmation: string): void {
    component.passwordForm.setValue({ newPassword, passwordConfirmation });
  }

  describe('username validation', () => {
    const isValid = (username: string): boolean => {
      component.usernameForm.get('username').setValue(username);
      return component.usernameForm.valid;
    };

    it('starts with the current username', () => {
      expect(component.usernameForm.get('username').value).toBe('alice');
    });

    it('accepts lower-case letters, digits, hyphen and underscore', () => {
      expect(isValid('abc')).toBeTrue();
      expect(isValid('bob_the-2nd')).toBeTrue();
      expect(isValid('a'.repeat(25))).toBeTrue();
    });

    it('rejects a blank, too short or too long username', () => {
      expect(isValid('')).toBeFalse();
      expect(component.usernameForm.get('username').errors).toEqual({ required: true });
      expect(isValid('ab')).toBeFalse();
      expect(component.usernameForm.get('username').hasError('minlength')).toBeTrue();
      expect(isValid('a'.repeat(26))).toBeFalse();
      expect(component.usernameForm.get('username').hasError('maxlength')).toBeTrue();
    });

    it('rejects upper case letters, spaces and other symbols', () => {
      expect(isValid('Alice')).toBeFalse();
      expect(component.usernameForm.get('username').hasError('pattern')).toBeTrue();
      expect(isValid('al ice')).toBeFalse();
      expect(isValid('al.ice')).toBeFalse();
      expect(isValid('al@ice')).toBeFalse();
    });
  });

  describe('password validation', () => {
    const isValid = (password: string): boolean => {
      setPasswords(password, password);
      return component.passwordForm.valid;
    };

    it('accepts a 6 to 50 character password with a digit, a lower and an upper case letter', () => {
      expect(isValid('Passw0')).toBeTrue();
      expect(isValid('Passw0rd1')).toBeTrue();
      expect(isValid('Aa1' + 'x'.repeat(47))).toBeTrue();
    });

    it('rejects a password that is too short or too long', () => {
      expect(isValid('Pa1xy')).toBeFalse();
      expect(component.passwordForm.get('newPassword').hasError('minlength')).toBeTrue();
      expect(isValid('Aa1' + 'x'.repeat(48))).toBeFalse();
      expect(component.passwordForm.get('newPassword').hasError('maxlength')).toBeTrue();
    });

    it('rejects a password missing a digit, a lower case letter or an upper case letter', () => {
      expect(isValid('Password')).toBeFalse();
      expect(isValid('PASSW0RD')).toBeFalse();
      expect(isValid('passw0rd')).toBeFalse();
      expect(component.passwordForm.get('newPassword').hasError('pattern')).toBeTrue();
    });

    it('rejects a password containing whitespace', () => {
      expect(isValid('Pass w0rd')).toBeFalse();
      expect(component.passwordForm.get('newPassword').hasError('pattern')).toBeTrue();
    });

    it('requires both fields', () => {
      setPasswords('', '');

      expect(component.passwordForm.get('newPassword').hasError('required')).toBeTrue();
      expect(component.passwordForm.get('passwordConfirmation').hasError('required')).toBeTrue();
    });
  });

  describe('checkPasswords', () => {
    it('flags the group as notSame while the confirmation differs', () => {
      setPasswords('Passw0rd', 'Passw0rd2');

      expect(component.passwordForm.errors).toEqual({ notSame: true });
      expect(component.passwordForm.invalid).toBeTrue();
    });

    it('clears the error once the confirmation matches', () => {
      setPasswords('Passw0rd', 'Passw0rd2');
      component.passwordForm.get('passwordConfirmation').setValue('Passw0rd');

      expect(component.passwordForm.errors).toBeNull();
      expect(component.passwordForm.valid).toBeTrue();
    });
  });

  describe('toggleVisibility', () => {
    it('flips one field between password and text without touching the other', () => {
      component.toggleVisibility(component.formUi.passwordElement);

      expect(component.formUi.passwordElement.inputType).toBe('text');
      expect(component.formUi.passwordElement.visiblePassword).toBeTrue();
      expect(component.formUi.passwordConfirmationElement.inputType).toBe('password');

      component.toggleVisibility(component.formUi.passwordElement);

      expect(component.formUi.passwordElement.inputType).toBe('password');
      expect(component.formUi.passwordElement.visiblePassword).toBeFalse();
    });
  });

  describe('updatePassword', () => {
    it('does nothing while the form is invalid', () => {
      setPasswords('weak', 'weak');

      component.updatePassword();

      expect(dangerModalService.modal).toBeUndefined();
      expect(profileService.updatePassword).not.toHaveBeenCalled();
    });

    it('asks for confirmation first and only changes the password once the modal confirms', () => {
      setPasswords('Passw0rd', 'Passw0rd');
      profileService.updatePassword.and.returnValue(of(LOGIN_INFO));

      component.updatePassword();

      expect(dangerModalService.modal).toEqual({ title: 'Update Password', action: 'Update', message: null });
      expect(profileService.updatePassword).not.toHaveBeenCalled();

      dangerModalService.call();

      expect(profileService.updatePassword).toHaveBeenCalledOnceWith('Passw0rd');
    });

    it('locks the form while the request runs', () => {
      setPasswords('Passw0rd', 'Passw0rd');
      const response = new Subject<LoginInfo>();
      profileService.updatePassword.and.returnValue(response);

      component.updatePassword();
      dangerModalService.call();

      expect(component.loading).toBeTrue();
      expect(component.passwordForm.disabled).toBeTrue();

      response.next(LOGIN_INFO);
      response.complete();

      expect(component.loading).toBeFalse();
      expect(component.passwordForm.enabled).toBeTrue();
    });

    it('shows a success toast and clears the form afterwards', () => {
      setPasswords('Passw0rd', 'Passw0rd');
      profileService.updatePassword.and.returnValue(of(LOGIN_INFO));

      component.updatePassword();
      dangerModalService.call();

      expect(toastService.show).toHaveBeenCalledOnceWith('Your password updated successfully', 'success');
      expect(component.passwordForm.value).toEqual({ newPassword: null, passwordConfirmation: null });
      expect(component.loading).toBeFalse();
    });

    it('shows the error and unlocks the form when the request fails', () => {
      setPasswords('Passw0rd', 'Passw0rd');
      profileService.updatePassword.and.returnValue(throwError(() => 'Password is too common'));

      component.updatePassword();
      dangerModalService.call();

      expect(toastService.show).toHaveBeenCalledOnceWith('Password is too common', 'error');
      expect(component.passwordForm.enabled).toBeTrue();
      expect(component.loading).toBeFalse();
    });
  });

  describe('updateUsername', () => {
    it('does nothing while the username is invalid', () => {
      component.usernameForm.get('username').setValue('Not Valid');

      component.updateUsername();

      expect(dangerModalService.modal).toBeUndefined();
      expect(profileService.updateUsername).not.toHaveBeenCalled();
    });

    it('asks for confirmation first and only sends the new username once the modal confirms', () => {
      component.usernameForm.get('username').setValue('alice2');
      profileService.updateUsername.and.returnValue(new Subject<LoginInfo>());

      component.updateUsername();

      expect(dangerModalService.modal).toEqual({ title: 'Change Username', action: 'Change', message: null });
      expect(profileService.updateUsername).not.toHaveBeenCalled();

      dangerModalService.call();

      expect(profileService.updateUsername).toHaveBeenCalledOnceWith('alice2');
      expect(component.loading).toBeTrue();
      expect(component.usernameForm.disabled).toBeTrue();
    });

    it('shows the error and unlocks the form when the request fails', () => {
      component.usernameForm.get('username').setValue('alice2');
      profileService.updateUsername.and.returnValue(throwError(() => 'Username is taken'));

      component.updateUsername();
      dangerModalService.call();

      expect(toastService.show).toHaveBeenCalledOnceWith('Username is taken', 'error');
      expect(component.usernameForm.enabled).toBeTrue();
      expect(component.loading).toBeFalse();
    });
  });
});

describe('AccountInfoComponent template', () => {
  it('spells "characters" in the password confirmation length message (RPS-1261)', async () => {
    const { fixture, el } = await renderComponent(
      AccountInfoComponent,
      [
        {
          provide: ProfileService,
          useValue: jasmine.createSpyObj<ProfileService>('ProfileService', ['updatePassword']),
        },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
      ],
      { username: 'alice' },
    );
    const confirmation = fixture.componentInstance.passwordForm.get('passwordConfirmation');
    confirmation.setValue('abc');
    confirmation.markAsTouched();
    fixture.detectChanges();

    const message = el.querySelector('[data-testid="profile-password-confirmation-error-minlength"]');
    expect(message?.textContent?.trim()).toBe('• Password confirmation should be minimum 6 characters');
  });
});
