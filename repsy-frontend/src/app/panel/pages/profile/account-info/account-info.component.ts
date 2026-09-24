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

import { NgClass } from '@angular/common';
import { Component, Input, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { LoginInfo } from '../../../../../generated/api';
import {
  PASSWORD_MESSAGES,
  PASSWORD_MISMATCH_MESSAGE,
  passwordValidators,
  USERNAME_MESSAGES,
  usernameValidators,
} from '../../../../shared/validators/credentials.validators';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { ProfileService } from '../service/profile.service';

class PasswordFormUiInputElement {
  public inputType = 'password';
  public visiblePassword = false;
}

export class PasswordFormUi {
  public readonly passwordElement = new PasswordFormUiInputElement();
  public readonly passwordConfirmationElement = new PasswordFormUiInputElement();
}

@Component({
  selector: 'app-account-info',
  imports: [ReactiveFormsModule, NgClass, RouterLink],
  host: { class: 'block' },
  templateUrl: './account-info.component.html',
})
export class AccountInfoComponent implements OnInit {
  @Input() passwordForm: FormGroup;
  @Input() usernameForm: FormGroup;
  @Input() username: string;

  public loading = false;

  public readonly formUi = new PasswordFormUi();
  public readonly usernameMessages = USERNAME_MESSAGES;
  public readonly passwordMessages = PASSWORD_MESSAGES;
  public readonly mismatchMessage = PASSWORD_MISMATCH_MESSAGE;

  constructor(
    private readonly fb: FormBuilder,
    private readonly profileFacadeService: ProfileService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.passwordForm = this.fb.group(
      {
        newPassword: ['', passwordValidators()],
        // The confirmation only has to be filled and equal to the new password (checkPasswords), so it
        // needs none of the password rules of its own.
        passwordConfirmation: ['', [Validators.required]],
      },
      {
        validator: this.checkPasswords,
      },
    );

    this.usernameForm = this.fb.group({
      username: ['', usernameValidators()],
    });
  }

  ngOnInit(): void {
    this.usernameForm.get('username').setValue(this.username);
  }

  private checkPasswords(group: FormGroup): { notSame: boolean } | null {
    const pass = group.get('newPassword')?.value;
    const confirmPass = group.get('passwordConfirmation')?.value;

    return pass === confirmPass ? null : { notSame: true };
  }

  toggleVisibility(element: PasswordFormUiInputElement) {
    element.visiblePassword = !element.visiblePassword;
    element.inputType = element.visiblePassword ? 'text' : 'password';
  }

  public updatePassword() {
    if (this.passwordForm.invalid) {
      return;
    }

    this.dangerModalService.show('Update Password', 'Update', () => {
      this.loading = true;
      this.passwordForm.disable();

      this.profileFacadeService
        .updatePassword(this.passwordForm.value.newPassword)
        .pipe(
          finalize(() => {
            this.passwordForm.enable();
            this.passwordForm.reset();
            this.loading = false;
          }),
        )
        .subscribe({
          next: () => {
            this.toastService.show('Your password updated successfully', 'success');
          },
          // The error interceptor already toasted the server's message; a second toast would repeat it.
          error: () => undefined,
        });
    });
  }

  public updateUsername() {
    if (this.usernameForm.invalid) {
      return;
    }

    this.dangerModalService.show('Change Username', 'Change', () => {
      this.loading = true;
      this.usernameForm.disable();

      const newUsername = this.usernameForm.get('username').value;

      this.profileFacadeService
        .updateUsername(newUsername)
        .pipe(
          finalize(() => {
            this.usernameForm.enable();
            this.loading = false;
          }),
        )
        .subscribe({
          next: (loginInfo: LoginInfo) => {
            localStorage.setItem('username', newUsername);
            localStorage.setItem('token', loginInfo.token);
            localStorage.setItem('refresh-token', loginInfo.refreshToken);
            this.toastService.show('Your username changed successfully', 'success');
            location.reload();
          },
          // The error interceptor already toasted the server's message; a second toast would repeat it.
          error: () => undefined,
        });
    });
  }
}
