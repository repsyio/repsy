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
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import moment from 'moment';
import { catchError, EMPTY, filter, finalize, map, Subject, Subscription, switchMap, timer } from 'rxjs';

import { PagedModelUserResponse, UserResponse } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import { DropdownComponent } from '../../../shared/components/dropdown/dropdown.component';
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

/** How long the search box must be idle before the typed text is sent to the server. */
export const USER_SEARCH_DEBOUNCE_MS = 250;

/** One request of the list: what the server is asked for. */
interface ListRequest {
  q: string;
  page: number;
}

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
    CommonModule,
    DropdownComponent,
    EmptyListComponent,
    SearchboxComponent,
  ],
  templateUrl: './user-management.component.html',
  styleUrl: './user-management.component.css',
})
export class UserManagementComponent implements OnInit, OnDestroy {
  public operationLock = false;
  public pageNum = 0;
  public pageSize = 10;
  public users: UserResponse[];
  public pagedData: PagedModelUserResponse = { page: { totalPages: 0 } };
  public showCreateUserModal = false;
  public showEditUserModal = false;
  public showResetPasswordModal = false;
  public selectedUser: UserResponse;
  /** The text of the search box: it is emptied whenever the list is loaded without the search, so box and list agree. */
  public searchQuery = '';
  /** The search the list currently shows; `searchQuery` runs ahead of it while the typing is debounced. */
  public appliedQuery = '';
  public newPassword: string;
  /** Admins on the server (not only on the loaded page); null until the first answer arrives. */
  public adminCount: number | null = null;

  private readonly requests = new Subject<ListRequest>();
  private readonly typedSearches = new Subject<string>();
  private readonly subscriptions = new Subscription();
  private adminCountSubscription?: Subscription;

  constructor(
    private readonly userService: UserService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly authService: AuthService,
  ) {
    // A request supersedes the one before it: switchMap unsubscribes from it, which cancels it on the wire,
    // so a slow answer of an old search never reaches the view.
    this.subscriptions.add(
      this.requests
        .pipe(
          switchMap((request) =>
            this.userService.listUsers(request.q || undefined, request.page, this.pageSize).pipe(
              // The HTTP error interceptor already shows the toast; the rows on screen stay.
              catchError(() => EMPTY),
            ),
          ),
        )
        .subscribe((pagedModel) => {
          this.pagedData = pagedModel;
          this.users = pagedModel.content ?? [];
        }),
    );

    // The typed text is sent once the box has been idle; a reload that emptied the box meanwhile drops it.
    this.subscriptions.add(
      this.typedSearches
        .pipe(
          switchMap((text) => timer(USER_SEARCH_DEBOUNCE_MS).pipe(map(() => text))),
          filter((text) => text === this.searchQuery),
        )
        .subscribe((text) => this.applySearch(text)),
    );
  }

  public ngOnInit(): void {
    this.fetchUsers();
  }

  public ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.adminCountSubscription?.unsubscribe();
  }

  /** Loads the current page of the current search, and the admin count that goes with it. */
  public fetchUsers(): void {
    this.requests.next({ q: this.appliedQuery, page: this.pageNum });
    this.adminCountSubscription?.unsubscribe();
    this.adminCountSubscription = this.userService.countAdmins().subscribe({
      next: (count) => {
        this.adminCount = count;
      },
      // The HTTP error interceptor already shows the failure; the last known count stays.
      error: () => {},
    });
  }

  public loadPage(pageNum: number): void {
    this.pageNum = pageNum;
    this.fetchUsers();
  }

  /** Called on every keystroke; the request goes out when the typing pauses, and it starts from the first page. */
  public search(username: string): void {
    this.searchQuery = username;
    this.typedSearches.next(username);
  }

  public refreshPage(): void {
    this.pageNum = 0;
    this.searchQuery = '';
    this.appliedQuery = '';
    this.fetchUsers();
  }

  /**
   * After an edit or a delete the list reloads without the old search: the user that was just renamed
   * or removed may no longer match it, which would empty the list. The page index goes back to the
   * first page only when there was a search, since it was counted within the searched list.
   */
  public resetSearch(): void {
    if (this.searchQuery || this.appliedQuery) {
      this.searchQuery = '';
      this.appliedQuery = '';
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
          // Deleting the only row of a later page leaves that page empty: go back to the first one.
          if (this.users.length === 1 && this.pageNum > 0) {
            this.pageNum = 0;
          }
          this.resetSearch();
          this.fetchUsers();
          this.toastService.show(successMsg, 'success');
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

  private applySearch(text: string): void {
    this.appliedQuery = text;
    this.pageNum = 0;
    this.fetchUsers();
  }

  protected readonly moment = moment;
}
