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

import { CommonModule, NgOptimizedImage } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';

import { ToastService } from '../../toast/toast.service';

@Component({
  selector: 'app-user-reset-password-modal',
  imports: [FormsModule, ReactiveFormsModule, NgOptimizedImage, CommonModule],
  standalone: true,
  templateUrl: './user-reset-password-modal.component.html',
  styleUrl: './user-reset-password-modal.component.css',
})
export class UserResetPasswordModalComponent {
  @Output() openChange = new EventEmitter<boolean>();
  @Input() public open: boolean;
  @Input() public username: string;
  @Input() public newPassword: string;

  public showPassword = false;

  constructor(private readonly toastService: ToastService) {}

  public closeModal(): void {
    this.showPassword = false;
    this.openChange.emit(false);
  }

  public togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  public copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.toastService.show('Copied to clipboard', 'success');
    });
  }
}
