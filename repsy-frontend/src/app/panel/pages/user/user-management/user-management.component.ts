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
import { Component, OnInit } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import moment from 'moment';
import { finalize } from 'rxjs';

import { PagedModelUserResponse, UserResponse } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import { DropdownComponent } from '../../../shared/components/dropdown/dropdown.component';
import { EllipsisPipe } from '../../../shared/components/ellipsis/ellipsis.pipe';
import { EmptyListComponent } from '../../../shared/components/empty-list/empty-list.component';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { UserCreateModalComponent } from '../../../shared/components/modals/user-create-modal/user-create-modal.component';
import { UserEditModalComponent } from '../../../shared/components/modals/user-edit-modal/user-edit-modal.component';
import { UserResetPasswordModalComponent } from '../../../shared/components/modals/user-reset-password-modal/user-reset-password-modal.component';
import { PaginationComponent } from '../../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../../shared/components/searchbox/searchbox.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../shared/components/tooltip/tooltip.component';
import { UserService } from '../service/user.service';

@Component({
  selector: 'app-user-management',
  imports: [
    FormsModule,
    ReactiveFormsModule,
    UserCreateModalComponent,
    UserEditModalComponent,
    UserResetPasswordModalComponent,
    PaginationComponent,
    NgOptimizedImage,
    TooltipComponent,
    EllipsisPipe,
    CommonModule,
    DropdownComponent,
    EmptyListComponent,
    SearchboxComponent,
  ],
  templateUrl: './user-management.component.html',
  styleUrl: './user-management.component.css',
})
export class UserManagementComponent implements OnInit {
  public operationLock = false;
  public pageNum = 0;
  public pageSize = 10;
  public users: UserResponse[];
  public pagedData: PagedModelUserResponse = { page: { totalPages: 0 } };
  public showCreateUserModal = false;
  public showEditUserModal = false;
  public showResetPasswordModal = false;
  public selectedUser: UserResponse;
  public searchQuery = '';
  public newPassword: string;
  /** Admins on the server (not only on the loaded page); null until the first answer arrives. */
  public adminCount: number | null = null;

  constructor(
    private readonly userService: UserService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly authService: AuthService,
  ) {}

  public ngOnInit(): void {
    this.fetchUsers();
  }

  public fetchUsers(): void {
    this.userService.listUsers(this.searchQuery || undefined, this.pageNum, this.pageSize).subscribe((pagedModel) => {
      this.pagedData = pagedModel;
      this.users = pagedModel.content ?? [];
    });
    this.userService.countAdmins().subscribe((count) => {
      this.adminCount = count;
    });
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchUsers();
  }

  public search(username: string): void {
    this.searchQuery = username;
    this.pageNum = 0;
    this.fetchUsers();
  }

  public refreshPage(): void {
    this.pageNum = 0;
    this.searchQuery = '';
    this.fetchUsers();
  }

  /**
   * After an edit or a delete the list reloads without the old search: the user that was just renamed
   * or removed may no longer match it, which would empty the list. The page index goes back to the
   * first page only when there was a search, since it was counted within the searched list.
   */
  public resetSearch(): void {
    if (this.searchQuery) {
      this.searchQuery = '';
      this.pageNum = 0;
    }
  }

  public userUpdated(): void {
    this.resetSearch();
    this.fetchUsers();
  }

  public createUser(): void {
    this.showCreateUserModal = true;
  }

  public editUser(user: UserResponse): void {
    this.selectedUser = user;
    this.showEditUserModal = true;
  }

  public resetPassword(user: UserResponse): void {
    this.dangerModalService.showWithMessage('Reset Password', 'Reset', this.resetPasswordMessage(user), () => {
      this.operationLock = true;

      this.userService
        .resetPassword(user.id)
        .pipe(
          finalize(() => {
            this.operationLock = false;
          }),
        )
        .subscribe((password) => {
          this.newPassword = password;
          this.selectedUser = user;
          this.showResetPasswordModal = true;
          this.toastService.show('Password reset successfully', 'success');
        });
    });
  }

  public resetPasswordMessage(user: UserResponse): string {
    const message =
      `A new random password for "${user.username}" is generated and shown to you once. ` +
      'The current password stops working and every signed-in session and CLI login of that account is revoked.';
    if (user.username !== this.authService.username) {
      return message;
    }
    return (
      `${message} This is your own account: you will be signed out and must sign in again with the new password. ` +
      'To keep your session, change it under Profile instead.'
    );
  }

  public deleteUser(user: UserResponse): void {
    // Check if trying to delete the last admin
    if (user.role === 'ADMIN' && this.isLastAdmin()) {
      this.toastService.show('Cannot delete the last admin user. Create another admin first.', 'error');
      return;
    }

    const successMsg = 'User deleted successfully';
    this.dangerModalService.show('Delete User', 'Delete', () => {
      this.operationLock = true;

      this.userService
        .deleteUser(user.id)
        .pipe(
          finalize(() => {
            this.operationLock = false;
          }),
        )
        .subscribe(() => {
          this.resetSearch();
          this.fetchUsers();
          this.toastService.show(successMsg, 'success');

          if (this.users.length === 1 && this.pageNum > 0) {
            this.pageNum = 0;
            this.fetchUsers();
          }
        });
    });
  }

  /**
   * Whether the admin about to be deleted or demoted is the last one on the server (RPS-1246). The
   * count comes from the server, so a search or a page holding a single admin does not matter. The
   * server still refuses the change for the real last admin, so an unknown count (null) lets it through.
   */
  protected isLastAdmin(): boolean {
    return this.adminCount !== null && this.adminCount <= 1;
  }

  public timeAgo(date: Date | string | null): string {
    if (!date) {
      return '';
    }
    return moment(date).fromNow();
  }

  public getRoleBadgeClass(role: string): string {
    switch (role) {
      case 'ADMIN':
        return 'badge-admin';
      case 'USER':
        return 'badge-user';
      default:
        return 'badge-default';
    }
  }

  protected readonly moment = moment;
}
