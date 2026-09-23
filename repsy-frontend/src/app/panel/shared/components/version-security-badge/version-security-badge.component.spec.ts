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
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { ScanStatus, Severity } from '../../../../../generated/api';
import { SecurityScanSupportService } from '../../service/security-scan-support.service';
import { VersionSecurityBadgeComponent } from './version-security-badge.component';

describe('VersionSecurityBadgeComponent', () => {
  let fixture: ComponentFixture<VersionSecurityBadgeComponent>;
  let component: VersionSecurityBadgeComponent;
  let supported: boolean;

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (): string => element().querySelector('button')?.textContent?.trim() ?? '';

  function render(inputs: Partial<VersionSecurityBadgeComponent>): void {
    Object.assign(component, inputs);
    fixture.detectChanges();
  }

  beforeEach(() => {
    supported = true;
    TestBed.configureTestingModule({
      imports: [VersionSecurityBadgeComponent],
      providers: [
        {
          provide: SecurityScanSupportService,
          useValue: { isSupported: () => of(supported) },
        },
      ],
    });
    fixture = TestBed.createComponent(VersionSecurityBadgeComponent);
    component = fixture.componentInstance;
    component.repoName = 'repo';
    component.repoType = 'MAVEN';
    component.artifactName = 'lib';
    component.versionName = '1.0.0';
  });

  it('shows the severity of a scanned version', () => {
    render({ scanned: true, severity: Severity.High, scanStatus: ScanStatus.Completed });

    expect(text()).toBe('High');
    expect(component.visible).toBeTrue();
  });

  for (const status of [ScanStatus.Pending, ScanStatus.Queued, ScanStatus.Running]) {
    it(`shows Scanning... for a version whose first scan is ${status}`, () => {
      render({ scanned: false, scanStatus: status });

      expect(component.firstScanInProgress).toBeTrue();
      expect(component.firstScanFailed).toBeFalse();
      expect(text()).toBe('Scanning...');
    });
  }

  it('shows Scan failed for a version whose first scan failed', () => {
    render({ scanned: false, scanStatus: ScanStatus.Failed });

    expect(component.firstScanInProgress).toBeFalse();
    expect(component.firstScanFailed).toBeTrue();
    expect(text()).toBe('Scan failed');
  });

  it('keeps the last known severity of a scanned version whose rescan is running', () => {
    render({ scanned: true, severity: Severity.Low, scanStatus: ScanStatus.Running });

    expect(component.firstScanInProgress).toBeFalse();
    expect(text()).toContain('Low');
  });

  it('shows nothing for a version without any scan', () => {
    render({ scanned: false, scanStatus: null });

    expect(component.visible).toBeFalse();
    expect(element().querySelector('button')).toBeNull();
  });

  it('shows nothing when the repository type cannot be scanned', () => {
    supported = false;

    render({ scanned: false, scanStatus: ScanStatus.Running });

    expect(element().querySelector('button')).toBeNull();
  });
});
