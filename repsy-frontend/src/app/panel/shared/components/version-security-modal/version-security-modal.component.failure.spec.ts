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
import { Router } from '@angular/router';
import { of } from 'rxjs';

import { ScanOverview, ScanStatus, VulnerabilityScanControllerService } from '../../../../../generated/api';
import { VersionSecurityModalComponent } from './version-security-modal.component';

/** RPS-1339: what the rendered modal shows for a version whose newest scan failed. */
describe('VersionSecurityModalComponent failed state', () => {
  let fixture: ComponentFixture<VersionSecurityModalComponent>;
  let scanService: jasmine.SpyObj<VulnerabilityScanControllerService>;

  function render(overview: ScanOverview): HTMLElement {
    scanService.getScanOverview.and.returnValue(of({ data: overview }) as never);
    fixture.componentRef.setInput('repoName', 'repo');
    fixture.componentRef.setInput('repoType', 'maven');
    fixture.componentRef.setInput('artifactName', 'org.acme:lib');
    fixture.componentRef.setInput('artifactVersion', '1.0.0');
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    return document.body;
  }

  beforeEach(() => {
    scanService = jasmine.createSpyObj<VulnerabilityScanControllerService>('VulnerabilityScanControllerService', [
      'getScanOverview',
    ]);
    TestBed.configureTestingModule({
      imports: [VersionSecurityModalComponent],
      providers: [
        { provide: VulnerabilityScanControllerService, useValue: scanService },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']) },
      ],
    });
    fixture = TestBed.createComponent(VersionSecurityModalComponent);
  });

  afterEach(() => fixture.destroy());

  it('shows why the newest scan failed', () => {
    const body = render({
      status: ScanStatus.Failed,
      errorMessage: 'Scanner adapter returned error: 503 SERVICE_UNAVAILABLE',
    });

    expect(body.querySelector('[data-testid="rescan-note"]')?.textContent).toContain('Failed');
    expect(body.querySelector('[data-testid="scan-failure-reason-text"]')?.textContent).toBe(
      'Scanner adapter returned error: 503 SERVICE_UNAVAILABLE',
    );
  });

  it('shows no reason line for a failed scan without a recorded reason', () => {
    const body = render({ status: ScanStatus.Failed });

    expect(body.querySelector('[data-testid="scan-failure-reason"]')).toBeNull();
  });

  it('shows no reason when the newest scan did not fail', () => {
    const body = render({ status: ScanStatus.Running, errorMessage: 'left over' });

    expect(body.querySelector('[data-testid="scan-failure-reason"]')).toBeNull();
  });
});
