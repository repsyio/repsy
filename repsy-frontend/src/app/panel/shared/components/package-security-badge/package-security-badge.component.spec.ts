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
import { SecurityService } from '../../../pages/security/services/security.service';
import { SecurityScanSupportService } from '../../services/security-scan-support.service';
import { PackageSecurityModalComponent } from '../package-security-modal/package-security-modal.component';
import { PackageSecurityBadgeComponent } from './package-security-badge.component';

describe('PackageSecurityBadgeComponent', () => {
  let fixture: ComponentFixture<PackageSecurityBadgeComponent>;
  let component: PackageSecurityBadgeComponent;
  let supported: boolean;
  let isSupported: jasmine.Spy;
  let securityService: jasmine.SpyObj<SecurityService>;

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const button = (): HTMLButtonElement | null => element().querySelector('button');

  function render(inputs: Partial<PackageSecurityBadgeComponent>): void {
    Object.assign(component, inputs);
    fixture.detectChanges();
  }

  beforeEach(() => {
    supported = true;
    isSupported = jasmine.createSpy('isSupported').and.callFake(() => of(supported));
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['getArtifactSecurityDetail']);
    TestBed.configureTestingModule({
      imports: [PackageSecurityBadgeComponent],
      providers: [
        { provide: SecurityScanSupportService, useValue: { isSupported } },
        { provide: SecurityService, useValue: securityService },
      ],
    });
    fixture = TestBed.createComponent(PackageSecurityBadgeComponent);
    component = fixture.componentInstance;
    component.repoName = 'repo';
    component.repoType = 'MAVEN';
    component.artifactName = 'lib';
    component.packageRoute = '/repo/lib';
  });

  it('asks whether the repository type can be scanned', () => {
    render({ scanned: true, severity: Severity.High });

    expect(isSupported).toHaveBeenCalledOnceWith('MAVEN');
  });

  it('shows a button with the severity of a scanned target', () => {
    render({ scanned: true, severity: Severity.High });

    expect(button()?.textContent).toContain('High');
    expect(element().querySelector('app-severity-badge')?.getAttribute('aria-label')).toBe('lib security status');
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

  it('opens the modal on click and lets the click reach the document, so an open row menu or selector closes (RPS-1565)', () => {
    render({ scanned: true, severity: Severity.High });
    expect(component.showModal).toBeFalse();
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    const stop = spyOn(click, 'stopPropagation').and.callThrough();
    const reachedDocument = jasmine.createSpy('documentClick');
    document.addEventListener('click', reachedDocument);

    try {
      button()?.dispatchEvent(click);
    } finally {
      document.removeEventListener('click', reachedDocument);
    }

    expect(stop).not.toHaveBeenCalled();
    expect(reachedDocument).toHaveBeenCalledTimes(1);
    expect(component.showModal).toBeTrue();
  });

  it('shows the modal once opened and closes it when the modal asks for that', () => {
    securityService.getArtifactSecurityDetail.and.returnValue(of({} as never));
    render({ scanned: true, severity: Severity.High });
    expect(fixture.debugElement.query(By.directive(PackageSecurityModalComponent)).componentInstance.open).toBeFalse();

    component.openModal();
    fixture.detectChanges();
    const modal = fixture.debugElement.query(By.directive(PackageSecurityModalComponent));
    expect(modal.componentInstance.open).toBeTrue();
    modal.componentInstance.closeModal();

    expect(component.showModal).toBeFalse();
  });
});
