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

import moment from 'moment';
import { of, Subject } from 'rxjs';

import { PagedModelUserResponse, UserResponse } from '../../../../../generated/api';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { UserService } from '../service/user.service';
import { UserManagementComponent } from './user-management.component';

function user(id: string, role: 'ADMIN' | 'USER' = 'USER'): UserResponse {
  return { id, username: `user-${id}`, role } as UserResponse;
}

function pageOf(content: UserResponse[] | undefined, totalPages = 1): PagedModelUserResponse {
  return { content, page: { number: 0, size: 10, totalElements: content?.length ?? 0, totalPages } };
}

describe('UserManagementComponent', () => {
  let component: UserManagementComponent;
  let userService: jasmine.SpyObj<UserService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    userService = jasmine.createSpyObj<UserService>('UserService', ['listUsers', 'deleteUser', 'resetPassword']);
    userService.listUsers.and.returnValue(of(pageOf([user('1'), user('2', 'ADMIN'), user('3', 'ADMIN')], 3)));
    userService.deleteUser.and.returnValue(of(undefined));
    userService.resetPassword.and.returnValue(of('N3w-Passw0rd'));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new UserManagementComponent(userService, toastService, dangerModalService);
  });

  describe('loading users', () => {
    it('starts with no users and a single empty page, and loads nothing before init', () => {
      expect(component.users).toBeUndefined();
      expect(component.pagedData.page?.totalPages).toBe(0);
      expect(userService.listUsers).not.toHaveBeenCalled();
    });

    it('loads the first page, ten at a time and without a search, on init', () => {
      component.ngOnInit();

      expect(userService.listUsers).toHaveBeenCalledOnceWith(undefined, 0, 10);
      expect(component.users.map((u) => u.id)).toEqual(['1', '2', '3']);
      expect(component.pagedData.page?.totalPages).toBe(3);
    });

    it('shows an empty list when the page has no content', () => {
      userService.listUsers.and.returnValue(of(pageOf(undefined)));

      component.ngOnInit();

      expect(component.users).toEqual([]);
    });

    it('loadPage fetches the requested page with the current search', () => {
      component.search('ali');

      component.loadPage(2);

      expect(component.pageNum).toBe(2);
      expect(userService.listUsers).toHaveBeenCalledWith('ali', 2, 10);
    });

    it('search restarts from the first page with the text', () => {
      component.loadPage(2);

      component.search('ali');

      expect(component.searchQuery).toBe('ali');
      expect(component.pageNum).toBe(0);
      expect(userService.listUsers).toHaveBeenCalledWith('ali', 0, 10);
    });

    it('an empty search is sent as no search at all', () => {
      component.search('');

      expect(userService.listUsers).toHaveBeenCalledWith(undefined, 0, 10);
    });

    it('refreshPage clears the search and goes back to the first page', () => {
      component.search('ali');
      component.loadPage(2);

      component.refreshPage();

      expect(component.searchQuery).toBe('');
      expect(component.pageNum).toBe(0);
      expect(userService.listUsers.calls.mostRecent().args).toEqual([undefined, 0, 10]);
    });
  });

  describe('the modals', () => {
    it('createUser opens the create modal', () => {
      component.createUser();

      expect(component.showCreateUserModal).toBeTrue();
    });

    it('editUser opens the edit modal for that user', () => {
      const chosen = user('2');

      component.editUser(chosen);

      expect(component.selectedUser).toBe(chosen);
      expect(component.showEditUserModal).toBeTrue();
    });
  });

  describe('resetPassword', () => {
    const target = user('2');

    it('asks for confirmation before resetting anything', () => {
      component.resetPassword(target);

      expect(dangerModalService.modal).toEqual({ title: 'Reset Password', action: 'Reset', message: null });
      expect(userService.resetPassword).not.toHaveBeenCalled();
    });

    it('resets once confirmed, shows the new password for that user and toasts', () => {
      component.resetPassword(target);

      dangerModalService.call();

      expect(userService.resetPassword).toHaveBeenCalledOnceWith('2');
      expect(component.newPassword).toBe('N3w-Passw0rd');
      expect(component.selectedUser).toBe(target);
      expect(component.showResetPasswordModal).toBeTrue();
      expect(toastService.show).toHaveBeenCalledOnceWith('Password reset successfully', 'success');
      expect(component.operationLock).toBeFalse();
    });

    it('holds the operation lock while the reset is running', () => {
      const answer = new Subject<string>();
      userService.resetPassword.and.returnValue(answer);
      component.resetPassword(target);

      dangerModalService.call();
      expect(component.operationLock).toBeTrue();

      answer.next('pw');
      answer.complete();
      expect(component.operationLock).toBeFalse();
    });
  });

  describe('deleteUser', () => {
    beforeEach(() => {
      component.ngOnInit();
      userService.listUsers.calls.reset();
    });

    it('asks for confirmation before deleting anything', () => {
      component.deleteUser(user('1'));

      expect(dangerModalService.modal).toEqual({ title: 'Delete User', action: 'Delete', message: null });
      expect(userService.deleteUser).not.toHaveBeenCalled();
    });

    it('deletes once confirmed, reloads and toasts', () => {
      component.deleteUser(user('1'));

      dangerModalService.call();

      expect(userService.deleteUser).toHaveBeenCalledOnceWith('1');
      expect(userService.listUsers).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('User deleted successfully', 'success');
      expect(component.operationLock).toBeFalse();
    });

    it('goes back to the first page when the last user of a later page is deleted', () => {
      userService.listUsers.and.returnValue(of(pageOf([user('9')], 2)));
      component.loadPage(1);
      component.deleteUser(user('9'));
      userService.listUsers.calls.reset();

      dangerModalService.call();

      expect(component.pageNum).toBe(0);
      expect(userService.listUsers.calls.allArgs()).toEqual([
        [undefined, 1, 10],
        [undefined, 0, 10],
      ]);
    });

    it('stays on the first page when its last user is deleted', () => {
      userService.listUsers.and.returnValue(of(pageOf([user('9')], 1)));
      component.loadPage(0);
      component.deleteUser(user('9'));
      userService.listUsers.calls.reset();

      dangerModalService.call();

      expect(userService.listUsers).toHaveBeenCalledTimes(1);
    });

    it('lets an admin be deleted while another admin is listed', () => {
      component.deleteUser(user('2', 'ADMIN'));

      expect(dangerModalService.modal?.title).toBe('Delete User');
      expect(toastService.show).not.toHaveBeenCalled();
    });

    it('refuses to delete the only admin listed, without asking for confirmation', () => {
      userService.listUsers.and.returnValue(of(pageOf([user('1'), user('2', 'ADMIN')])));
      component.ngOnInit();

      component.deleteUser(user('2', 'ADMIN'));

      expect(toastService.show).toHaveBeenCalledOnceWith(
        'Cannot delete the last admin user. Create another admin first.',
        'error',
      );
      expect(dangerModalService.modal).toBeUndefined();
      expect(userService.deleteUser).not.toHaveBeenCalled();
    });

    it('never blocks deleting a plain user', () => {
      userService.listUsers.and.returnValue(of(pageOf([user('1'), user('2', 'ADMIN')])));
      component.ngOnInit();

      component.deleteUser(user('1'));

      expect(dangerModalService.modal?.title).toBe('Delete User');
    });
  });

  describe('helpers', () => {
    it('timeAgo renders a relative time, and nothing for no date', () => {
      expect(component.timeAgo(moment().subtract(3, 'days').toDate())).toBe('3 days ago');
      expect(component.timeAgo(null)).toBe('');
    });

    it('getRoleBadgeClass has a class per role and a default', () => {
      expect(component.getRoleBadgeClass('ADMIN')).toBe('badge-admin');
      expect(component.getRoleBadgeClass('USER')).toBe('badge-user');
      expect(component.getRoleBadgeClass('OTHER')).toBe('badge-default');
    });
  });
});
