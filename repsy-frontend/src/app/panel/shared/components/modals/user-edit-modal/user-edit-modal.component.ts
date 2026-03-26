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

import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ReactiveFormsModule } from '@angular/forms';

import { UserInfo } from '../../../../pages/user/dto/user.info';
import { UserUpdateForm } from '../../../../pages/user/form/user-update-form';
import { UserService } from '../../../../pages/user/service/user.service';
import { ToastService } from '../../toast/toast.service';
import { ToggleComponent } from '../../toggle/toggle.component';

@Component({
  selector: 'app-user-edit-modal',
  imports: [ReactiveFormsModule, ToggleComponent],
  standalone: true,
  templateUrl: './user-edit-modal.component.html',
  styleUrl: './user-edit-modal.component.css',
})
export class UserEditModalComponent implements OnChanges {
  @Output() openChange = new EventEmitter<boolean>();
  @Output() updated = new EventEmitter<void>();
  @Input() public open: boolean;
  @Input() public user: UserInfo;
  @Input() public isLastAdmin = false;

  public loading = false;
  public form: FormGroup;

  constructor(
    private readonly fb: FormBuilder,
    private readonly userService: UserService,
    private readonly toastService: ToastService,
  ) {
    this.form = this.fb.group({
      username: [
        '',
        [Validators.required, Validators.minLength(3), Validators.maxLength(25), Validators.pattern(/^[a-z0-9_\-]+$/)],
      ],
      isAdmin: [false],
    });
  }

  public ngOnChanges(changes: SimpleChanges): void {
    if ((changes['user'] || changes['open']) && this.user && this.open) {
      this.form.patchValue({
        username: this.user.username,
        isAdmin: this.user.role === 'ADMIN',
      });

      if (this.isLastAdmin) {
        this.form.get('isAdmin')?.disable();
      } else {
        this.form.get('isAdmin')?.enable();
      }
    }
  }

  public closeModal(): void {
    this.form.reset();
    this.form.get('isAdmin')?.enable();
    this.openChange.emit(false);
  }

  public updateUser(): void {
    if (this.form.invalid) {
      return;
    }

    if (this.user.role === 'ADMIN' && this.isLastAdmin && !this.form.get('isAdmin')?.value) {
      this.toastService.show('Cannot remove admin role from the last admin. Create another admin first.', 'error');
      return;
    }

    this.loading = true;
    this.form.disable();

    const formValue = this.form.getRawValue(); // getRawValue to get disabled fields too
    const updateForm: UserUpdateForm = {
      username: formValue.username,
      role: formValue.isAdmin ? 'ADMIN' : 'USER',
    };

    this.userService
      .updateUser(this.user.id, updateForm)
      .then(() => {
        this.toastService.show('User updated successfully', 'success');
        this.updated.emit();
        this.closeModal();
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.form.enable();
        if (this.isLastAdmin) {
          this.form.get('isAdmin')?.disable();
        }
        this.loading = false;
      });
  }
}
