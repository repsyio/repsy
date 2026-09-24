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

import { ComponentFixture, fakeAsync, flush, TestBed, tick } from '@angular/core/testing';
import moment from 'moment';
import { NEVER, of, Subject, throwError } from 'rxjs';

import { PagedModelUserResponse, UserResponse } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { UserService } from '../service/user.service';
import { USER_SEARCH_DEBOUNCE_MS, UserManagementComponent } from './user-management.component';

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
    userService = jasmine.createSpyObj<UserService>('UserService', [
      'listUsers',
      'countAdmins',
      'deleteUser',
      'resetPassword',
    ]);
    userService.listUsers.and.returnValue(of(pageOf([user('1'), user('2', 'ADMIN'), user('3', 'ADMIN')], 3)));
    userService.countAdmins.and.returnValue(of(2));
    userService.deleteUser.and.returnValue(of(undefined));
    userService.resetPassword.and.returnValue(of('N3w-Passw0rd'));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new UserManagementComponent(userService, toastService, dangerModalService, {
      username: 'admin',
    } as AuthService);
  });

  afterEach(() => component.ngOnDestroy());

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

    it('loads the admin count from the server together with the list', () => {
      userService.countAdmins.and.returnValue(of(5));

      component.ngOnInit();

      expect(component.adminCount).toBe(5);
    });

    it('does not derive the admin count from the loaded page (RPS-1246)', () => {
      userService.listUsers.and.returnValue(of(pageOf([user('1'), user('2', 'ADMIN')])));
      userService.countAdmins.and.returnValue(of(4));

      component.ngOnInit();

      expect(component.adminCount).toBe(4);
    });

    it('shows an empty list when the page has no content', () => {
      userService.listUsers.and.returnValue(of(pageOf(undefined)));

      component.ngOnInit();

      expect(component.users).toEqual([]);
    });

    it('loadPage fetches the requested page with the current search', fakeAsync(() => {
      component.search('ali');
      tick(USER_SEARCH_DEBOUNCE_MS);

      component.loadPage(2);

      expect(component.pageNum).toBe(2);
      expect(userService.listUsers).toHaveBeenCalledWith('ali', 2, 10);
    }));

    it('search restarts from the first page with the text', fakeAsync(() => {
      component.loadPage(2);

      component.search('ali');
      tick(USER_SEARCH_DEBOUNCE_MS);

      expect(component.searchQuery).toBe('ali');
      expect(component.appliedQuery).toBe('ali');
      expect(component.pageNum).toBe(0);
      expect(userService.listUsers).toHaveBeenCalledWith('ali', 0, 10);
    }));

    it('an empty search is sent as no search at all', fakeAsync(() => {
      component.search('');
      tick(USER_SEARCH_DEBOUNCE_MS);

      expect(userService.listUsers).toHaveBeenCalledWith(undefined, 0, 10);
    }));

    it('refreshPage clears the search and goes back to the first page', fakeAsync(() => {
      component.search('ali');
      tick(USER_SEARCH_DEBOUNCE_MS);
      component.loadPage(2);

      component.refreshPage();

      expect(component.searchQuery).toBe('');
      expect(component.appliedQuery).toBe('');
      expect(component.pageNum).toBe(0);
      expect(userService.listUsers.calls.mostRecent().args).toEqual([undefined, 0, 10]);
    }));
  });

  describe('typing into the search box', () => {
    it('sends nothing while the typing goes on, and one request for the last text once it pauses', fakeAsync(() => {
      component.search('a');
      tick(USER_SEARCH_DEBOUNCE_MS - 50);
      component.search('al');
      tick(USER_SEARCH_DEBOUNCE_MS - 50);
      component.search('ali');
      tick(USER_SEARCH_DEBOUNCE_MS - 1);

      expect(userService.listUsers).not.toHaveBeenCalled();

      tick(1);

      expect(userService.listUsers).toHaveBeenCalledOnceWith('ali', 0, 10);
    }));

    it('drops the text that a reload emptied the box of meanwhile', fakeAsync(() => {
      component.search('ali');

      component.refreshPage();
      userService.listUsers.calls.reset();
      tick(USER_SEARCH_DEBOUNCE_MS);

      expect(userService.listUsers).not.toHaveBeenCalled();
      expect(component.searchQuery).toBe('');
    }));

    it('drops the text when an edit reloaded the list meanwhile', fakeAsync(() => {
      component.search('ali');

      component.userUpdated();
      userService.listUsers.calls.reset();
      tick(USER_SEARCH_DEBOUNCE_MS);

      expect(userService.listUsers).not.toHaveBeenCalled();
    }));

    it('keeps paging within the search that is shown, not the text still being typed', fakeAsync(() => {
      component.search('ali');
      tick(USER_SEARCH_DEBOUNCE_MS);
      component.search('alic');

      component.loadPage(1);

      expect(userService.listUsers.calls.mostRecent().args).toEqual(['ali', 1, 10]);
      flush();
    }));

    it('stops reacting once the page is gone', fakeAsync(() => {
      component.search('ali');

      component.ngOnDestroy();
      tick(USER_SEARCH_DEBOUNCE_MS);

      expect(userService.listUsers).not.toHaveBeenCalled();
    }));
  });

  describe('overlapping requests', () => {
    let answers: Subject<PagedModelUserResponse>[];

    beforeEach(() => {
      answers = [];
      userService.listUsers.and.callFake(() => {
        const answer = new Subject<PagedModelUserResponse>();
        answers.push(answer);
        return answer;
      });
    });

    it('never lets the slow answer of an old search overwrite the newer one', fakeAsync(() => {
      component.search('a');
      tick(USER_SEARCH_DEBOUNCE_MS);
      component.search('ab');
      tick(USER_SEARCH_DEBOUNCE_MS);
      expect(userService.listUsers.calls.allArgs()).toEqual([
        ['a', 0, 10],
        ['ab', 0, 10],
      ]);

      answers[1].next(pageOf([user('ab')]));
      answers[0].next(pageOf([user('a1'), user('a2')]));

      expect(component.users.map((u) => u.id)).toEqual(['ab']);
      expect(answers[0].observed).toBeFalse();
    }));

    it('shows the newer answer even when the older one never comes', fakeAsync(() => {
      component.search('a');
      tick(USER_SEARCH_DEBOUNCE_MS);
      component.search('ab');
      tick(USER_SEARCH_DEBOUNCE_MS);

      answers[1].next(pageOf([user('ab')]));

      expect(component.users.map((u) => u.id)).toEqual(['ab']);
    }));

    it('cancels the request of the page that was left when another page is asked for', () => {
      component.loadPage(1);
      component.loadPage(2);

      answers[1].next(pageOf([user('p2')]));
      answers[0].next(pageOf([user('p1')]));

      expect(answers[0].observed).toBeFalse();
      expect(component.users.map((u) => u.id)).toEqual(['p2']);
    });

    it('does not let a search answer overwrite the list a refresh loaded after it', fakeAsync(() => {
      component.search('a');
      tick(USER_SEARCH_DEBOUNCE_MS);

      component.refreshPage();
      answers[1].next(pageOf([user('all')]));
      answers[0].next(pageOf([user('a1')]));

      expect(component.users.map((u) => u.id)).toEqual(['all']);
    }));

    it('keeps the rows on screen and stays usable when a request fails', fakeAsync(() => {
      component.ngOnInit();
      answers[0].next(pageOf([user('1')]));
      userService.listUsers.and.returnValues(
        throwError(() => new Error('boom')),
        of(pageOf([user('2')])),
      );

      component.loadPage(1);
      expect(component.users.map((u) => u.id)).toEqual(['1']);

      component.loadPage(2);
      expect(component.users.map((u) => u.id)).toEqual(['2']);
    }));
  });

  describe('after an edit', () => {
    it('reloads without the old search, from the first page, so a renamed user stays listed', fakeAsync(() => {
      component.search('user-1');
      tick(USER_SEARCH_DEBOUNCE_MS);
      component.loadPage(2);
      userService.listUsers.calls.reset();

      component.userUpdated();

      expect(component.searchQuery).toBe('');
      expect(component.pageNum).toBe(0);
      expect(userService.listUsers).toHaveBeenCalledOnceWith(undefined, 0, 10);
    }));

    it('keeps the page when there was no search, since it is a page of the same list', () => {
      component.loadPage(2);
      userService.listUsers.calls.reset();

      component.userUpdated();

      expect(component.pageNum).toBe(2);
      expect(userService.listUsers).toHaveBeenCalledOnceWith(undefined, 2, 10);
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

      expect(dangerModalService.modal.title).toBe('Reset Password');
      expect(dangerModalService.modal.action).toBe('Reset');
      expect(dangerModalService.modal.message).toContain(`"${target.username}"`);
      expect(dangerModalService.modal.message).toContain('shown to you once');
      expect(userService.resetPassword).not.toHaveBeenCalled();
    });

    it('warns when the row is the signed-in admin', () => {
      component.resetPassword({ id: '9', username: 'admin', role: 'ADMIN' } as UserResponse);

      expect(dangerModalService.modal.message).toContain('your own account');
      expect(dangerModalService.modal.message).toContain('signed out');
    });

    it('does not add the own-account warning for another user', () => {
      component.resetPassword(target);

      expect(dangerModalService.modal.message).not.toContain('your own account');
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

    it('reloads without the old search, from the first page', fakeAsync(() => {
      component.search('user-1');
      tick(USER_SEARCH_DEBOUNCE_MS);
      component.loadPage(1);
      component.deleteUser(user('1'));
      userService.listUsers.calls.reset();

      dangerModalService.call();

      expect(component.searchQuery).toBe('');
      expect(userService.listUsers.calls.allArgs()).toEqual([[undefined, 0, 10]]);
    }));

    it('goes back to the first page when the last user of a later page is deleted', () => {
      userService.listUsers.and.returnValue(of(pageOf([user('9')], 2)));
      component.loadPage(1);
      component.deleteUser(user('9'));
      userService.listUsers.calls.reset();

      dangerModalService.call();

      expect(component.pageNum).toBe(0);
      expect(userService.listUsers.calls.allArgs()).toEqual([[undefined, 0, 10]]);
    });

    it('stays on the first page when its last user is deleted', () => {
      userService.listUsers.and.returnValue(of(pageOf([user('9')], 1)));
      component.loadPage(0);
      component.deleteUser(user('9'));
      userService.listUsers.calls.reset();

      dangerModalService.call();

      expect(userService.listUsers).toHaveBeenCalledTimes(1);
    });

    it('lets an admin be deleted while another admin exists', () => {
      component.deleteUser(user('2', 'ADMIN'));

      expect(dangerModalService.modal?.title).toBe('Delete User');
      expect(toastService.show).not.toHaveBeenCalled();
    });

    it('lets the only admin of the loaded page be deleted when the server counts more admins (RPS-1246)', () => {
      userService.listUsers.and.returnValue(of(pageOf([user('1'), user('2', 'ADMIN')])));
      userService.countAdmins.and.returnValue(of(3));
      component.ngOnInit();

      component.deleteUser(user('2', 'ADMIN'));

      expect(dangerModalService.modal?.title).toBe('Delete User');
      expect(toastService.show).not.toHaveBeenCalled();
    });

    it('refuses to delete the last admin on the server, without asking for confirmation', () => {
      userService.countAdmins.and.returnValue(of(1));
      component.ngOnInit();

      component.deleteUser(user('2', 'ADMIN'));

      expect(toastService.show).toHaveBeenCalledOnceWith(
        'Cannot delete the last admin user. Create another admin first.',
        'error',
      );
      expect(dangerModalService.modal).toBeUndefined();
      expect(userService.deleteUser).not.toHaveBeenCalled();
    });

    it('leaves the decision to the server while the admin count is unknown', () => {
      userService.countAdmins.and.returnValue(NEVER);
      component.ngOnInit();

      component.deleteUser(user('2', 'ADMIN'));

      expect(dangerModalService.modal?.title).toBe('Delete User');
    });

    it('reloads the admin count after a delete', () => {
      component.deleteUser(user('2', 'ADMIN'));
      userService.countAdmins.calls.reset();
      userService.countAdmins.and.returnValue(of(1));

      dangerModalService.call();

      expect(userService.countAdmins).toHaveBeenCalledTimes(1);
      expect(component.adminCount).toBe(1);
    });

    it('never blocks deleting a plain user', () => {
      userService.countAdmins.and.returnValue(of(1));
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

describe('UserManagementComponent search box', () => {
  let fixture: ComponentFixture<UserManagementComponent>;
  let userService: jasmine.SpyObj<UserService>;

  const box = (): HTMLInputElement => fixture.nativeElement.querySelector('[data-testid="user-search"] input');

  /** Types the text and lets the debounce pass, so the list request has gone out. */
  function type(text: string): void {
    box().value = text;
    box().dispatchEvent(new Event('input'));
    tick(USER_SEARCH_DEBOUNCE_MS);
    fixture.detectChanges();
  }

  beforeEach(() => {
    userService = jasmine.createSpyObj<UserService>('UserService', [
      'listUsers',
      'countAdmins',
      'deleteUser',
      'resetPassword',
    ]);
    userService.listUsers.and.returnValue(of(pageOf([user('1'), user('2', 'ADMIN')])));
    userService.countAdmins.and.returnValue(of(2));
    TestBed.configureTestingModule({
      imports: [UserManagementComponent],
      providers: [
        { provide: UserService, useValue: userService },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
      ],
    });
    fixture = TestBed.createComponent(UserManagementComponent);
    fixture.detectChanges();
  });

  it('is emptied by the refresh button together with the query', fakeAsync(() => {
    type('user-1');
    expect(userService.listUsers).toHaveBeenCalledWith('user-1', 0, 10);

    fixture.nativeElement.querySelector('[data-testid="user-refresh"]').click();
    fixture.detectChanges();

    expect(box().value).toBe('');
    expect(userService.listUsers.calls.mostRecent().args).toEqual([undefined, 0, 10]);
  }));

  it('is emptied when the list reloads after an edit', fakeAsync(() => {
    type('user-1');

    fixture.componentInstance.userUpdated();
    fixture.detectChanges();

    expect(box().value).toBe('');
    expect(userService.listUsers.calls.mostRecent().args).toEqual([undefined, 0, 10]);
  }));

  it('keeps the typed text while it is the query', fakeAsync(() => {
    type('user-1');

    expect(box().value).toBe('user-1');
  }));

  it('does not send a request per keystroke', fakeAsync(() => {
    userService.listUsers.calls.reset();

    box().value = 'u';
    box().dispatchEvent(new Event('input'));
    box().value = 'us';
    box().dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(userService.listUsers).not.toHaveBeenCalled();
    tick(USER_SEARCH_DEBOUNCE_MS);
    expect(userService.listUsers).toHaveBeenCalledOnceWith('us', 0, 10);
  }));

  it('names the applied search, not the text still being typed, in the empty list message', fakeAsync(() => {
    userService.listUsers.and.returnValue(of(pageOf([])));
    type('nobody');

    box().value = 'nobod';
    box().dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No user matches “nobody”.');
    flush();
  }));
});
