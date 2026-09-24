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

import { ChangeDetectorRef } from '@angular/core';
import { Subject } from 'rxjs';

import { RepoSecuritySummary, Severity } from '../../../../../generated/api';
import { SecurityService } from '../../security/service/security.service';
import { SecurityOverviewCardComponent } from './security-overview-card.component';

// The dashboard is rendered inside an OnPush component, so this card is only checked again when it marks
// itself. Its answer is often the last one to arrive, and nothing else marks the view after it.
describe('SecurityOverviewCardComponent', () => {
  let answer: Subject<Record<string, RepoSecuritySummary>>;
  let cdRef: jasmine.SpyObj<ChangeDetectorRef>;
  let card: SecurityOverviewCardComponent;

  beforeEach(() => {
    answer = new Subject();
    cdRef = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['markForCheck']);
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['getSecuritySummary']);
    securityService.getSecuritySummary.and.returnValue(answer);
    card = new SecurityOverviewCardComponent(securityService, cdRef);
    card.ngOnInit();
  });

  it('is loading, and has not marked the view, until the summary answers', () => {
    expect(card.loading).toBeTrue();
    expect(cdRef.markForCheck).not.toHaveBeenCalled();
  });

  it('counts the repositories and the critical or high ones, then marks the view for a check', () => {
    answer.next({
      a: { severity: Severity.Critical } as RepoSecuritySummary,
      b: { severity: Severity.High } as RepoSecuritySummary,
      c: { severity: Severity.Low } as RepoSecuritySummary,
      d: {} as RepoSecuritySummary,
    });
    answer.complete();

    expect(card.loading).toBeFalse();
    expect(card.totalRepoCount).toBe(4);
    expect(card.criticalOrHighCount).toBe(2);
    expect(cdRef.markForCheck).toHaveBeenCalled();
  });

  it('stops loading and marks the view for a check when the summary fails', () => {
    answer.error(new Error('boom'));

    expect(card.loading).toBeFalse();
    expect(card.totalRepoCount).toBe(0);
    expect(cdRef.markForCheck).toHaveBeenCalled();
  });
});
