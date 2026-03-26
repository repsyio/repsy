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

import { Component } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { ProfileService } from '../service/profile.service';

@Component({
  selector: 'app-delete-account',
  imports: [RouterLink],
  templateUrl: './delete-account.component.html',
  standalone: true,
})
export class DeleteAccountComponent {
  public loading = false;

  constructor(
    private readonly router: Router,
    private readonly profileService: ProfileService,
    private readonly toastService: ToastService,
    private readonly authService: AuthService,
    private readonly dangerModalService: DangerModalService,
  ) {}

  public confirmAccountDelete(): void {
    this.dangerModalService.show('Delete Account', 'Delete', () => this.deleteAccount());
  }

  private deleteAccount(): void {
    this.loading = true;

    this.profileService
      .deleteAccount()
      .then(() => {
        this.toastService.show('Account deleted successfully.', 'success');
        this.authService.logOut();
        this.router.navigateByUrl('/login');
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }
}
