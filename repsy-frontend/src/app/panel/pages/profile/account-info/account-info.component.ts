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

import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { LoginInfo } from '../../../shared/dto/login-info';
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

  constructor(
    private readonly fb: FormBuilder,
    private readonly profileService: ProfileService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.passwordForm = this.fb.group(
      {
        newPassword: [
          '',
          [
            Validators.required,
            Validators.minLength(6),
            Validators.maxLength(50),
            Validators.pattern(/^(?=.*\d)(?=.*[a-z])(?=.*[A-Z])(?=\S+$).+$/),
          ],
        ],
        passwordConfirmation: [
          '',
          [
            Validators.required,
            Validators.minLength(6),
            Validators.maxLength(50),
            Validators.pattern(/^(?=.*\d)(?=.*[a-z])(?=.*[A-Z])(?=\S+$).+$/),
          ],
        ],
      },
      {
        validator: this.checkPasswords,
      },
    );

    this.usernameForm = this.fb.group({
      username: [
        '',
        [Validators.required, Validators.minLength(3), Validators.maxLength(25), Validators.pattern(/^[a-z0-9_-]+$/)],
      ],
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

      this.profileService
        .updatePassword(this.passwordForm.value.newPassword)
        .then(() => {
          this.toastService.show('Your password updated successfully', 'success');
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.passwordForm.enable();
          this.passwordForm.reset();
          this.loading = false;
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

      this.profileService
        .updateUsername(newUsername)
        .then((loginInfo: LoginInfo) => {
          localStorage.setItem('username', newUsername);
          localStorage.setItem('token', loginInfo.token);
          localStorage.setItem('refresh-token', loginInfo.refreshToken);
          this.toastService.show('Your username changed successfully', 'success');
          location.reload();
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.usernameForm.enable();
          this.loading = false;
        });
    });
  }
}
