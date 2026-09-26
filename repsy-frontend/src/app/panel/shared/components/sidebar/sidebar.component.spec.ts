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
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, throwError } from 'rxjs';

import { ProfileInfo } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import { ProfileService } from '../../../pages/profile/service/profile.service';
import { SidebarComponent } from './sidebar.component';

/**
 * How the dashboard renders it: at "/" the layout (and the sidebar in it) lives in the view container of
 * the OnPush `AuthRedirectComponent`, so nothing refreshes the sidebar unless it marks itself dirty.
 */
@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SidebarComponent],
  template: '<app-sidebar />',
})
class OnPushHostComponent {}

describe('SidebarComponent (RPS-1456)', () => {
  let fixture: ComponentFixture<OnPushHostComponent>;
  const link = (testId: string): HTMLElement | null => fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);

  const create = (get: () => unknown): void => {
    TestBed.configureTestingModule({
      imports: [OnPushHostComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { logOut: jasmine.createSpy('logOut') } },
        { provide: ProfileService, useValue: { get } },
      ],
    });
    fixture = TestBed.createComponent(OnPushHostComponent);
    fixture.detectChanges();
  };

  it('shows Users and Security once the admin profile arrives, without anything else marking the view', () => {
    const profile = new Subject<Pick<ProfileInfo, 'role'>>();
    create(() => profile.asObservable());
    expect(link('sidebar-link-repositories')).not.toBeNull();
    expect(link('sidebar-link-users')).toBeNull();

    // The profile answer is the last event of the page: the OnPush host is not dirty, no other check follows.
    profile.next({ role: 'ADMIN' });
    fixture.detectChanges();

    expect(link('sidebar-link-users')).not.toBeNull();
    expect(link('sidebar-link-security')).not.toBeNull();
  });

  it('keeps Users and Security hidden for a USER', () => {
    const profile = new Subject<Pick<ProfileInfo, 'role'>>();
    create(() => profile.asObservable());

    profile.next({ role: 'USER' });
    fixture.detectChanges();

    expect(link('sidebar-link-repositories')).not.toBeNull();
    expect(link('sidebar-link-users')).toBeNull();
    expect(link('sidebar-link-security')).toBeNull();
  });

  it('keeps Users and Security hidden when the profile cannot be loaded', () => {
    spyOn(console, 'error');
    create(() => throwError(() => new Error('offline')));

    expect(link('sidebar-link-users')).toBeNull();
    expect(link('sidebar-link-security')).toBeNull();
    expect(console.error).toHaveBeenCalled();
  });
});
