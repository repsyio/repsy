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

import { AbstractControl, FormBuilder } from '@angular/forms';
import { Router } from '@angular/router';

import { LoginComponent } from '../../auth/pages/login/login.component';
import { AuthService } from '../../auth/pages/service/auth.service';
import { AccountInfoComponent } from '../../panel/pages/profile/account-info/account-info.component';
import { ProfileService } from '../../panel/pages/profile/service/profile.service';
import { RepoInfoComponent } from '../../panel/pages/repository/repo-settings/repo-info/repo-info.component';
import { renderComponent } from '../../panel/pages/repository/testing/render-spec-helpers';
import { UserService } from '../../panel/pages/user/service/user.service';
import { DangerModalService } from '../../panel/shared/components/modals/danger-modal/danger-modal.service';
import { DeployTokenCreateModalComponent } from '../../panel/shared/components/modals/deploy-token-create-modal/deploy-token-create-modal.component';
import { RepositoryCreateModalComponent } from '../../panel/shared/components/modals/repository-create-modal/repository-create-modal.component';
import { UserCreateModalComponent } from '../../panel/shared/components/modals/user-create-modal/user-create-modal.component';
import { UserEditModalComponent } from '../../panel/shared/components/modals/user-edit-modal/user-edit-modal.component';
import { ToastService } from '../../panel/shared/components/toast/toast.service';
import {
  LOGIN_USERNAME_MESSAGES,
  PASSWORD_MESSAGES,
  PASSWORD_MISMATCH_MESSAGE,
  REQUIRED_MESSAGE,
  USERNAME_MESSAGES,
} from './credentials.validators';
import { DESCRIPTION_MAX_MESSAGE } from './description.validators';

// RPS-1265: every form shows the same sentence for the same rule. These specs render the real
// templates and read the message a user sees, so a form that keeps its own wording fails here.

const toast = { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) };

/** Sets `value` on `control`, marks it touched and returns the trimmed text of the message `testId`. */
type Show = (control: AbstractControl, value: string, testId: string) => string | undefined;

function showFor(el: HTMLElement, detect: () => void): Show {
  return (control, value, testId) => {
    control.setValue(value);
    control.markAsTouched();
    detect();
    return el.querySelector(`[data-testid="${testId}"]`)?.textContent?.trim();
  };
}

const bullet = (message: string): string => `• ${message}`;

const TOO_LONG_USERNAME = 'a'.repeat(26);
const TOO_LONG_PASSWORD = 'Aa1' + 'x'.repeat(48);

describe('the username messages of the create-user, edit-user, profile and token forms', () => {
  it('are the same sentences in the create-user modal', async () => {
    const { fixture, el } = await renderComponent(
      UserCreateModalComponent,
      [{ provide: UserService, useValue: jasmine.createSpyObj<UserService>('UserService', ['createUser']) }, toast],
      { open: true },
    );
    const show = showFor(el, () => fixture.detectChanges());
    const username = fixture.componentInstance.form.get('username');

    expect(show(username, '', 'user-create-username-error-required')).toBe(bullet(USERNAME_MESSAGES.required));
    expect(show(username, 'ab', 'user-create-username-error-minlength')).toBe(bullet(USERNAME_MESSAGES.minlength));
    expect(show(username, TOO_LONG_USERNAME, 'user-create-username-error-maxlength')).toBe(
      bullet(USERNAME_MESSAGES.maxlength),
    );
    expect(show(username, 'Bad Name', 'user-create-username-error-pattern')).toBe(bullet(USERNAME_MESSAGES.pattern));
  });

  it('are the same sentences in the edit-user modal', async () => {
    const { fixture, el } = await renderComponent(
      UserEditModalComponent,
      [{ provide: UserService, useValue: jasmine.createSpyObj<UserService>('UserService', ['updateUser']) }, toast],
      { open: true, user: { id: 'u1', username: 'bob', role: 'USER' } },
    );
    const show = showFor(el, () => fixture.detectChanges());
    const username = fixture.componentInstance.form.get('username');

    expect(show(username, '', 'user-edit-username-error-required')).toBe(bullet(USERNAME_MESSAGES.required));
    expect(show(username, 'ab', 'user-edit-username-error-minlength')).toBe(bullet(USERNAME_MESSAGES.minlength));
    expect(show(username, TOO_LONG_USERNAME, 'user-edit-username-error-maxlength')).toBe(
      bullet(USERNAME_MESSAGES.maxlength),
    );
    expect(show(username, 'Bad Name', 'user-edit-username-error-pattern')).toBe(bullet(USERNAME_MESSAGES.pattern));
  });

  it('are the same sentences in the profile', async () => {
    const { fixture, el } = await renderComponent(
      AccountInfoComponent,
      [
        {
          provide: ProfileService,
          useValue: jasmine.createSpyObj<ProfileService>('ProfileService', ['updateUsername']),
        },
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
        toast,
      ],
      { username: 'alice' },
    );
    const show = showFor(el, () => fixture.detectChanges());
    const username = fixture.componentInstance.usernameForm.get('username');

    expect(show(username, '', 'profile-username-error-required')).toBe(bullet(USERNAME_MESSAGES.required));
    expect(show(username, 'ab', 'profile-username-error-minlength')).toBe(bullet(USERNAME_MESSAGES.minlength));
    expect(show(username, TOO_LONG_USERNAME, 'profile-username-error-maxlength')).toBe(
      bullet(USERNAME_MESSAGES.maxlength),
    );
    expect(show(username, 'Bad Name', 'profile-username-error-pattern')).toBe(bullet(USERNAME_MESSAGES.pattern));
  });

  it('are the same sentences in the deploy token modal, where the username is optional', async () => {
    const { fixture, el } = await renderComponent(DeployTokenCreateModalComponent, [toast], {
      open: true,
      repoName: 'repo',
      repoType: 'MAVEN',
    });
    const show = showFor(el, () => fixture.detectChanges());
    const username = fixture.componentInstance.form.get('username');

    expect(show(username, '', 'token-create-username-error-required')).toBeUndefined();
    expect(username.valid).toBeTrue();
    expect(show(username, 'ab', 'token-create-username-error-minlength')).toBe(bullet(USERNAME_MESSAGES.minlength));
    expect(show(username, TOO_LONG_USERNAME, 'token-create-username-error-maxlength')).toBe(
      bullet(USERNAME_MESSAGES.maxlength),
    );
    expect(show(username, 'Bad Name', 'token-create-username-error-pattern')).toBe(bullet(USERNAME_MESSAGES.pattern));
  });
});

describe('the password messages of the create-user, profile and login forms', () => {
  it('are the same sentences in the create-user modal, including the mismatch', async () => {
    const { fixture, el } = await renderComponent(
      UserCreateModalComponent,
      [{ provide: UserService, useValue: jasmine.createSpyObj<UserService>('UserService', ['createUser']) }, toast],
      { open: true },
    );
    const show = showFor(el, () => fixture.detectChanges());
    const form = fixture.componentInstance.form;

    expect(show(form.get('password'), '', 'user-create-password-error-required')).toBe(
      bullet(PASSWORD_MESSAGES.required),
    );
    expect(show(form.get('password'), 'Ab1', 'user-create-password-error-minlength')).toBe(
      bullet(PASSWORD_MESSAGES.minlength),
    );
    expect(show(form.get('password'), TOO_LONG_PASSWORD, 'user-create-password-error-maxlength')).toBe(
      bullet(PASSWORD_MESSAGES.maxlength),
    );
    expect(show(form.get('password'), 'abcdefgh', 'user-create-password-error-pattern')).toBe(
      bullet(PASSWORD_MESSAGES.pattern),
    );
    form.get('password').setValue('Passw0rd');
    expect(show(form.get('confirmPassword'), 'different', 'user-create-confirm-password-error-mismatch')).toBe(
      bullet(PASSWORD_MISMATCH_MESSAGE),
    );
  });

  it('are the same sentences in the profile, including the mismatch', async () => {
    const { fixture, el } = await renderComponent(
      AccountInfoComponent,
      [
        {
          provide: ProfileService,
          useValue: jasmine.createSpyObj<ProfileService>('ProfileService', ['updatePassword']),
        },
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
        toast,
      ],
      { username: 'alice' },
    );
    const show = showFor(el, () => fixture.detectChanges());
    const form = fixture.componentInstance.passwordForm;

    expect(show(form.get('newPassword'), '', 'profile-new-password-error-required')).toBe(
      bullet(PASSWORD_MESSAGES.required),
    );
    expect(show(form.get('newPassword'), 'Ab1', 'profile-new-password-error-minlength')).toBe(
      bullet(PASSWORD_MESSAGES.minlength),
    );
    expect(show(form.get('newPassword'), TOO_LONG_PASSWORD, 'profile-new-password-error-maxlength')).toBe(
      bullet(PASSWORD_MESSAGES.maxlength),
    );
    expect(show(form.get('newPassword'), 'abcdefgh', 'profile-new-password-error-pattern')).toBe(
      bullet(PASSWORD_MESSAGES.pattern),
    );
    form.get('newPassword').setValue('Passw0rd');
    expect(show(form.get('passwordConfirmation'), '', 'profile-password-confirmation-error-required')).toBe(
      bullet(PASSWORD_MESSAGES.required),
    );
    expect(show(form.get('passwordConfirmation'), 'different', 'profile-password-confirmation-error-mismatch')).toBe(
      bullet(PASSWORD_MISMATCH_MESSAGE),
    );
  });

  it('leave the profile confirmation with no rule of its own besides "required" and "equal"', async () => {
    const { fixture } = await renderComponent(
      AccountInfoComponent,
      [
        {
          provide: ProfileService,
          useValue: jasmine.createSpyObj<ProfileService>('ProfileService', ['updatePassword']),
        },
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
        toast,
      ],
      { username: 'alice' },
    );
    const form = fixture.componentInstance.passwordForm;

    form.setValue({ newPassword: 'Passw0rd', passwordConfirmation: 'Passw0rd' });
    expect(form.valid).toBeTrue();
    form.get('passwordConfirmation').setValue('abc');
    expect(Object.keys(form.get('passwordConfirmation').errors ?? {})).toEqual([]);
    expect(form.hasError('notSame')).toBeTrue();
  });

  it('are the same sentences on the login form, which the backend holds to the same rules', async () => {
    const { fixture, el } = await renderComponent(LoginComponent, [
      { provide: AuthService, useValue: jasmine.createSpyObj<AuthService>('AuthService', ['logIn']) },
      toast,
    ]);
    // The login component is OnPush, so type into the real inputs: that marks it for a check.
    const type = (field: 'username' | 'password', value: string, testId: string): string | undefined => {
      const input = el.querySelector(`[data-testid="login-${field}"]`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new Event('blur'));
      fixture.detectChanges();
      return el.querySelector(`[data-testid="${testId}"]`)?.textContent?.trim();
    };

    expect(type('username', '', 'login-username-error-required')).toBe(bullet(LOGIN_USERNAME_MESSAGES.required));
    expect(type('username', 'ab', 'login-username-error-minlength')).toBe(bullet(LOGIN_USERNAME_MESSAGES.minlength));
    expect(type('username', 'a'.repeat(151), 'login-username-error-maxlength')).toBe(
      bullet(LOGIN_USERNAME_MESSAGES.maxlength),
    );
    expect(type('username', 'bad user', 'login-username-error-pattern')).toBe(bullet(LOGIN_USERNAME_MESSAGES.pattern));
    expect(type('password', '', 'login-password-error-required')).toBe(bullet(PASSWORD_MESSAGES.required));
    expect(type('password', 'Ab1', 'login-password-error-minlength')).toBe(bullet(PASSWORD_MESSAGES.minlength));
    expect(type('password', TOO_LONG_PASSWORD, 'login-password-error-maxlength')).toBe(
      bullet(PASSWORD_MESSAGES.maxlength),
    );
    expect(type('password', 'abcdefgh', 'login-password-error-pattern')).toBe(bullet(PASSWORD_MESSAGES.pattern));
  });

  it('accept the same password everywhere: what create-user takes, login takes, and the reverse', () => {
    const login = new LoginComponent({} as Router, new FormBuilder(), {} as AuthService, {} as ToastService);
    login.ngOnInit();
    const create = new UserCreateModalComponent({} as UserService, new FormBuilder(), {} as ToastService);

    for (const password of ['Passw0', 'Aa1' + 'x'.repeat(47), 'Pa1xy', 'password1', 'Pass w0rd', TOO_LONG_PASSWORD]) {
      login.form.get('password').setValue(password);
      create.form.get('password').setValue(password);
      expect(login.form.get('password').valid).withContext(password).toBe(create.form.get('password').valid);
    }
  });
});

describe('the description of a repository and of a deploy token', () => {
  const TOO_LONG = 'd'.repeat(501);

  it('has a counter and no maxlength attribute in the create-repository modal, so the message can show', async () => {
    const { fixture, el } = await renderComponent(RepositoryCreateModalComponent, [toast], { open: true });
    const show = showFor(el, () => fixture.detectChanges());
    const description = fixture.componentInstance.form.get('description');
    const textarea = el.querySelector('[data-testid="repo-create-description"]');
    const counter = (): string | undefined =>
      el.querySelector('[data-testid="repo-create-description-counter"]')?.textContent?.trim();

    expect(textarea?.hasAttribute('maxlength')).toBeFalse();
    expect(counter()).toBe('0/500');
    expect(show(description, 'd'.repeat(500), 'repo-create-description-error-maxlength')).toBeUndefined();
    expect(counter()).toBe('500/500');
    expect(show(description, TOO_LONG, 'repo-create-description-error-maxlength')).toBe(
      bullet(DESCRIPTION_MAX_MESSAGE),
    );
    expect(counter()).toBe('501/500');
    expect(description.valid).toBeFalse();
  });

  it('keeps pasted text of any length in the create-repository textarea', async () => {
    const { fixture, el } = await renderComponent(RepositoryCreateModalComponent, [toast], { open: true });
    const textarea = el.querySelector('[data-testid="repo-create-description"]') as HTMLTextAreaElement;

    textarea.value = TOO_LONG;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.componentInstance.form.get('description').value).toBe(TOO_LONG);
  });

  it('has the same counter and message in the deploy token modal', async () => {
    const { fixture, el } = await renderComponent(DeployTokenCreateModalComponent, [toast], {
      open: true,
      repoName: 'repo',
      repoType: 'MAVEN',
    });
    const show = showFor(el, () => fixture.detectChanges());
    const description = fixture.componentInstance.form.get('description');

    expect(el.querySelector('[data-testid="token-create-description"]')?.hasAttribute('maxlength')).toBeFalse();
    expect(show(description, TOO_LONG, 'token-create-description-error-maxlength')).toBe(
      bullet(DESCRIPTION_MAX_MESSAGE),
    );
    expect(el.querySelector('[data-testid="token-create-description-counter"]')?.textContent?.trim()).toBe('501/500');
  });

  it('has the same counter and message in the repository settings', async () => {
    const { fixture, el } = await renderComponent(
      RepoInfoComponent,
      [
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
        toast,
      ],
      {
        repoType: 'MAVEN',
        activeRepository: { repoName: 'my-repo', description: 'hello', canRead: true, canWrite: true, canManage: true },
      },
    );
    const show = showFor(el, () => fixture.detectChanges());
    const description = fixture.componentInstance.descriptionForm.get('description');
    const counter = (): string | undefined =>
      el.querySelector('[data-testid="settings-description-counter"]')?.textContent?.trim();

    expect(el.querySelector('[data-testid="settings-description-input"]')?.hasAttribute('maxlength')).toBeFalse();
    expect(counter()).toBe('5/500');
    expect(show(description, TOO_LONG, 'settings-description-error-maxlength')).toBe(bullet(DESCRIPTION_MAX_MESSAGE));
    expect(counter()).toBe('501/500');
  });
});

describe('the deploy token name', () => {
  it('has no dead minLength branch: an empty name is "required" and nothing else', async () => {
    const { fixture, el } = await renderComponent(DeployTokenCreateModalComponent, [toast], {
      open: true,
      repoName: 'repo',
      repoType: 'MAVEN',
    });
    const show = showFor(el, () => fixture.detectChanges());
    const name = fixture.componentInstance.form.get('name');

    expect(show(name, '', 'token-create-name-error-required')).toBe(bullet(REQUIRED_MESSAGE));
    expect(Object.keys(name.errors ?? {})).toEqual(['required']);
    expect(show(name, 'n'.repeat(81), 'token-create-name-error-maxlength')).toBe('• Should be maximum 80 characters');
  });
});
