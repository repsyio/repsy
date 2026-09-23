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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';

import { Severity } from '../../../../../generated/api';
import { SecurityService } from '../../../pages/security/service/security.service';
import { SecurityScanSupportService } from '../../service/security-scan-support.service';
import { RepoSecurityModalComponent } from '../repo-security-modal/repo-security-modal.component';
import { RepoSecurityBadgeComponent } from './repo-security-badge.component';

describe('RepoSecurityBadgeComponent', () => {
  let fixture: ComponentFixture<RepoSecurityBadgeComponent>;
  let component: RepoSecurityBadgeComponent;
  let supported: boolean;
  let isSupported: jasmine.Spy;
  let securityService: jasmine.SpyObj<SecurityService>;

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const button = (): HTMLButtonElement | null => element().querySelector('button');

  function render(inputs: Partial<RepoSecurityBadgeComponent>): void {
    Object.assign(component, inputs);
    fixture.detectChanges();
  }

  beforeEach(() => {
    supported = true;
    isSupported = jasmine.createSpy('isSupported').and.callFake(() => of(supported));
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['getRepoSecurityDetail']);
    TestBed.configureTestingModule({
      imports: [RepoSecurityBadgeComponent],
      providers: [
        { provide: SecurityScanSupportService, useValue: { isSupported } },
        { provide: SecurityService, useValue: securityService },
      ],
    });
    fixture = TestBed.createComponent(RepoSecurityBadgeComponent);
    component = fixture.componentInstance;
    component.repoName = 'repo';
    component.repoType = 'MAVEN';
  });

  it('asks whether the repository type can be scanned', () => {
    render({ scanned: true, severity: Severity.High });

    expect(isSupported).toHaveBeenCalledOnceWith('MAVEN');
  });

  it('shows a button with the severity of a scanned target', () => {
    render({ scanned: true, severity: Severity.High });

    expect(button()?.textContent).toContain('High');
    expect(element().querySelector('app-severity-badge')?.getAttribute('aria-label')).toBe('repo security status');
  });

  it('shows the button while a first scan is unfinished', () => {
    render({ scanned: false, unscannedInProgressCount: 2 });

    expect(button()).not.toBeNull();
  });

  it('shows the button when a first scan failed', () => {
    render({ scanned: false, unscannedFailedCount: 1 });

    expect(button()).not.toBeNull();
  });

  it('shows nothing when nothing was scanned and nothing is being scanned', () => {
    render({ scanned: false, unscannedInProgressCount: 0, unscannedFailedCount: null });

    expect(button()).toBeNull();
  });

  it('shows nothing when the repository type cannot be scanned', () => {
    supported = false;

    render({ scanned: true, severity: Severity.High });

    expect(button()).toBeNull();
  });

  it('opens the modal on click, without letting the click reach the row behind it', () => {
    render({ scanned: true, severity: Severity.High });
    expect(component.showModal).toBeFalse();
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    const stop = spyOn(click, 'stopPropagation').and.callThrough();

    button()?.dispatchEvent(click);

    expect(stop).toHaveBeenCalled();
    expect(component.showModal).toBeTrue();
  });

  it('shows the modal once opened and closes it when the modal asks for that', () => {
    securityService.getRepoSecurityDetail.and.returnValue(of({} as never));
    render({ scanned: true, severity: Severity.High });
    expect(fixture.debugElement.query(By.directive(RepoSecurityModalComponent)).componentInstance.open).toBeFalse();

    component.openModal(new Event('click'));
    fixture.detectChanges();
    const modal = fixture.debugElement.query(By.directive(RepoSecurityModalComponent));
    expect(modal.componentInstance.open).toBeTrue();
    modal.componentInstance.closeModal();

    expect(component.showModal).toBeFalse();
  });
});
