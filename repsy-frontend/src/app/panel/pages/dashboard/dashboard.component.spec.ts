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
import { Subject } from 'rxjs';

import {
  RepoCollectionControllerService,
  RepoListInfo,
  RepoSecuritySummary,
  RepoType,
  TotalUsageInfo,
} from '../../../../generated/api';
import { AuthService } from '../../../auth/pages/service/auth.service';
import { SplashService } from '../../../shared/components/splash-screen/splasht.service';
import { ProfileService } from '../profile/service/profile.service';
import { SecurityService } from '../security/service/security.service';
import { DashboardComponent } from './dashboard.component';
import { UsageService } from './service/usage.service';

/**
 * How "/" renders the dashboard: `AuthRedirectComponent` (OnPush) creates it in a view container, so nothing
 * refreshes it unless one of its components marks its view (RPS-1456 sidebar, RPS-1459 audit). Every source
 * below answers after the first check and is the last event of the page: its own output must show.
 */
@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DashboardComponent],
  template: '<app-dashboard />',
})
class OnPushHostComponent {}

describe('DashboardComponent in an OnPush host (RPS-1459)', () => {
  let fixture: ComponentFixture<OnPushHostComponent>;
  let profileAnswers: Subject<{ role: string }>[];
  let usage: Subject<TotalUsageInfo>;
  let counts: Subject<{ data: Record<string, number> }>;
  let recent: Subject<{ data: { content: RepoListInfo[] } }>;
  let security: Subject<Record<string, RepoSecuritySummary>>;

  const query = (testId: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  const text = (testId: string): string | undefined => query(testId)?.textContent?.trim();

  beforeEach(() => {
    profileAnswers = [];
    usage = new Subject();
    counts = new Subject();
    recent = new Subject();
    security = new Subject();
    TestBed.configureTestingModule({
      imports: [OnPushHostComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { isAuthenticated: () => true, username: 'admin', logOut: () => {} } },
        {
          // The sidebar and the dashboard content each ask for the profile.
          provide: ProfileService,
          useValue: {
            get: () => {
              const answer = new Subject<{ role: string }>();
              profileAnswers.push(answer);
              return answer.asObservable();
            },
          },
        },
        { provide: UsageService, useValue: { getTotalUsage: () => usage.asObservable() } },
        {
          provide: RepoCollectionControllerService,
          useValue: { getRepoCounts: () => counts.asObservable(), listRepos: () => recent.asObservable() },
        },
        { provide: SecurityService, useValue: { getSecuritySummary: () => security.asObservable() } },
        { provide: SplashService, useValue: { setLoading: false } },
      ],
    });
    fixture = TestBed.createComponent(OnPushHostComponent);
    fixture.detectChanges();
  });

  it('starts empty: no request has answered yet', () => {
    expect(profileAnswers.length).toBe(2);
    expect(query('dashboard-create-repo')).toBeNull();
    expect(query('sidebar-link-users')).toBeNull();
    expect(text('disk-usage-repo-count')).toBe('0');
    expect(text('repo-count-value-maven')).toBe('0');
    expect(query('recent-activity-row-my-repo')).toBeNull();
    expect(text('security-overview-total')).toBeUndefined();
  });

  it('shows the disk usage when it answers', () => {
    usage.next({ diskUsed: { bytes: 1024, text: '1 KB' }, reposCount: 4 } as TotalUsageInfo);
    fixture.detectChanges();

    expect(text('disk-usage-total')).toBe('1 KB');
    expect(text('disk-usage-repo-count')).toBe('4');
  });

  it('shows the repository counts when they answer', () => {
    counts.next({ data: { [RepoType.Maven]: 3, [RepoType.Npm]: 2 } });
    fixture.detectChanges();

    expect(text('repo-count-value-maven')).toBe('3');
    expect(text('repo-count-value-npm')).toBe('2');
  });

  it('lists the recent repositories when they answer', () => {
    recent.next({
      data: {
        content: [{ name: 'my-repo', type: RepoType.Maven, diskUsage: 1024, createdAt: new Date().toISOString() }],
      },
    });
    fixture.detectChanges();

    expect(text('row-name')).toBe('my-repo');
  });

  it('shows the security overview when it answers', () => {
    security.next({ a: {} as RepoSecuritySummary, b: {} as RepoSecuritySummary });
    security.complete();
    fixture.detectChanges();

    expect(text('security-overview-total')).toBe('2');
  });

  it('shows the admin controls of the dashboard content and of the sidebar as their profile answers arrive', () => {
    profileAnswers[0].next({ role: 'ADMIN' });
    fixture.detectChanges();
    profileAnswers[1].next({ role: 'ADMIN' });
    fixture.detectChanges();

    expect(query('dashboard-create-repo')).not.toBeNull();
    expect(query('sidebar-link-users')).not.toBeNull();
    expect(query('sidebar-link-security')).not.toBeNull();
  });
});
