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

import { DropdownComponent } from '../../../shared/components/dropdown/dropdown.component';
import { EllipsisPipe } from '../../../shared/components/ellipsis/ellipsis.pipe';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { UserCreateModalComponent } from '../../../shared/components/modals/user-create-modal/user-create-modal.component';
import { UserEditModalComponent } from '../../../shared/components/modals/user-edit-modal/user-edit-modal.component';
import { UserResetPasswordModalComponent } from '../../../shared/components/modals/user-reset-password-modal/user-reset-password-modal.component';
import { PaginationComponent } from '../../../shared/components/pagination/pagination.component';
import { SearchboxComponent } from '../../../shared/components/searchbox/searchbox.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { TooltipComponent } from '../../../shared/components/tooltip/tooltip.component';
import { PagedData } from '../../../shared/dto/paged-data';
import { UserInfo } from '../dto/user.info';
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
    SearchboxComponent,
  ],
  templateUrl: './user-management.component.html',
  styleUrl: './user-management.component.css',
})
export class UserManagementComponent implements OnInit {
  public operationLock = false;
  public pageNum = 0;
  public pageSize = 10;
  public users: UserInfo[];
  public pagedData: PagedData<UserInfo>;
  public showCreateUserModal = false;
  public showEditUserModal = false;
  public showResetPasswordModal = false;
  public selectedUser: UserInfo;
  public searchQuery = '';
  public newPassword: string;

  constructor(
    private readonly userService: UserService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
  ) {
    this.pagedData = new PagedData<UserInfo>();
  }

  public ngOnInit(): void {
    this.fetchUsers();
  }

  public fetchUsers(): void {
    this.userService
      .getUsers(this.pageNum, this.pageSize, this.searchQuery)
      .then((pageData: PagedData<UserInfo>) => {
        this.pagedData.page = pageData.page;
        this.users = pageData.content;
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
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

  public createUser(): void {
    this.showCreateUserModal = true;
  }

  public editUser(user: UserInfo): void {
    this.selectedUser = user;
    this.showEditUserModal = true;
  }

  public resetPassword(user: UserInfo): void {
    this.dangerModalService.show('Reset Password', 'Reset', () => {
      this.operationLock = true;

      this.userService
        .resetPassword(user.id)
        .then((password: string) => {
          this.newPassword = password;
          this.selectedUser = user;
          this.showResetPasswordModal = true;
          this.toastService.show('Password reset successfully', 'success');
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.operationLock = false;
        });
    });
  }

  public deleteUser(user: UserInfo): void {
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
        .then(() => {
          this.fetchUsers();
          this.toastService.show(successMsg, 'success');

          if (this.users.length === 1 && this.pageNum > 0) {
            this.pageNum = 0;
            this.fetchUsers();
          }
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.operationLock = false;
        });
    });
  }

  protected isLastAdmin(): boolean {
    const adminCount = this.users?.filter((u) => u.role === 'ADMIN').length || 0;
    return adminCount === 1;
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
