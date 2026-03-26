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
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ReactiveFormsModule } from '@angular/forms';

import { UserCreateForm } from '../../../../pages/user/form/user-crete-form';
import { UserService } from '../../../../pages/user/service/user.service';
import { ToastService } from '../../toast/toast.service';
import { ToggleComponent } from '../../toggle/toggle.component';

@Component({
  selector: 'app-user-create-modal',
  imports: [ReactiveFormsModule, ToggleComponent, NgClass],
  standalone: true,
  templateUrl: './user-create-modal.component.html',
  styleUrl: './user-create-modal.component.css',
})
export class UserCreateModalComponent {
  @Output() openChange = new EventEmitter<boolean>();
  @Output() created = new EventEmitter<void>();
  @Input() public open: boolean;

  public loading = false;
  public form: FormGroup;
  public showPassword = false;
  public showConfirmPassword = false;

  constructor(
    private readonly userService: UserService,
    private readonly fb: FormBuilder,
    private readonly toastService: ToastService,
  ) {
    this.form = this.fb.group(
      {
        username: [
          '',
          [
            Validators.required,
            Validators.minLength(3),
            Validators.maxLength(25),
            Validators.pattern(/^[a-z0-9_\-]+$/),
          ],
        ],
        password: [
          '',
          [
            Validators.required,
            Validators.minLength(6),
            Validators.maxLength(50),
            Validators.pattern(/^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=\S+$).+$/),
          ],
        ],
        confirmPassword: ['', [Validators.required]],
        isAdmin: [false],
      },
      { validators: this.passwordMatchValidator },
    );
  }

  private passwordMatchValidator(form: FormGroup): Record<string, boolean> | null {
    const password = form.get('password');
    const confirmPassword = form.get('confirmPassword');

    if (!password || !confirmPassword) {
      return null;
    }

    if (password.value !== confirmPassword.value) {
      confirmPassword.setErrors({ passwordMismatch: true });
      return { passwordMismatch: true };
    }

    // Clear the error if passwords match
    if (confirmPassword.hasError('passwordMismatch')) {
      confirmPassword.setErrors(null);
    }

    return null;
  }

  public closeModal(): void {
    this.form.reset();
    this.form.patchValue({ isAdmin: false });
    this.showPassword = false;
    this.showConfirmPassword = false;
    this.openChange.emit(false);
  }

  public createUser(): void {
    if (this.form.invalid) {
      return;
    }

    this.loading = true;
    this.form.disable();

    const payload: UserCreateForm = {
      username: this.form.value.username.trim(),
      password: this.form.value.password,
      role: this.form.value.isAdmin ? 'ADMIN' : 'USER',
    };

    this.userService
      .createUser(payload)
      .then(() => {
        this.toastService.show('User created successfully.', 'success');
        this.closeModal();
        this.created.emit();
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.form.enable();
        this.loading = false;
      });
  }

  public togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  public toggleConfirmPasswordVisibility(): void {
    this.showConfirmPassword = !this.showConfirmPassword;
  }
}
