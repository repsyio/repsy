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

import { ViewportScroller } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';

import {
  ScanOverview,
  ScanStatus,
  VulnerabilityScanControllerService,
  VulnerabilityScanDetail,
  VulnerabilityScanInfo,
} from '../../../../../generated/api';
import { restResponse } from '../../../pages/repository/testing/protocol-service-spec-helpers';
import { SecurityScanSupportService } from '../../service/security-scan-support.service';
import { ToastService } from '../toast/toast.service';
import { SecurityScanSectionComponent } from './security-scan-section.component';

const REPO = 'acme-repo';
const reply = (data: unknown): never => of(restResponse(data)) as never;

/** RPS-1339: what the rendered failed state shows next to the Re-scan action. */
describe('SecurityScanSectionComponent failed state', () => {
  let fixture: ComponentFixture<SecurityScanSectionComponent>;
  let api: jasmine.SpyObj<VulnerabilityScanControllerService>;

  function render(status: ScanStatus, errorMessage?: string): HTMLElement {
    const info: VulnerabilityScanInfo = { id: 'scan-1', status, repoName: REPO, errorMessage };
    const detail: VulnerabilityScanDetail = { id: 'scan-1', status, repoName: REPO, errorMessage };
    const overview: ScanOverview = { scanId: 'scan-1', status };
    api.listVulnerabilityScans.and.callFake(() => reply({ content: [info], page: { totalPages: 1 } }));
    api.getScanOverview.and.callFake(() => reply(overview));
    api.getVulnerabilityScan.and.callFake((() => reply(detail)) as never);
    api.getVulnerabilityScanFindings.and.callFake(() =>
      reply({ content: [], page: { totalPages: 0, totalElements: 0 } }),
    );

    fixture.componentRef.setInput('repoType', 'npm');
    fixture.componentRef.setInput('repoName', REPO);
    fixture.componentRef.setInput('artifactName', 'left-pad');
    fixture.componentRef.setInput('artifactVersion', '1.0.0');
    fixture.componentRef.setInput('canTriggerScan', true);
    fixture.componentInstance.expanded = true;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    api = jasmine.createSpyObj<VulnerabilityScanControllerService>('VulnerabilityScanControllerService', [
      'listVulnerabilityScans',
      'getScanOverview',
      'getVulnerabilityScan',
      'getVulnerabilityScanFindings',
      'triggerVulnerabilityScan',
    ]);
    const support = jasmine.createSpyObj<SecurityScanSupportService>('SecurityScanSupportService', ['isSupported']);
    support.isSupported.and.returnValue(of(true));

    TestBed.configureTestingModule({
      imports: [SecurityScanSectionComponent],
      providers: [
        { provide: VulnerabilityScanControllerService, useValue: api },
        { provide: SecurityScanSupportService, useValue: support },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
        {
          provide: ViewportScroller,
          useValue: jasmine.createSpyObj<ViewportScroller>('ViewportScroller', ['scrollToAnchor']),
        },
        { provide: ActivatedRoute, useValue: { fragment: of(null), snapshot: { fragment: null } } },
      ],
    });
    fixture = TestBed.createComponent(SecurityScanSectionComponent);
  });

  it('shows why the scan failed, next to the Re-scan action', () => {
    const el = render(ScanStatus.Failed, 'stub scanner: simulated scan failure');

    expect(el.querySelector('[data-testid="scan-section-status"]')?.textContent).toContain('Failed');
    expect(el.querySelector('[data-testid="scan-failure-reason-text"]')?.textContent).toBe(
      'stub scanner: simulated scan failure',
    );
    expect(el.querySelector('[data-testid="scan-section-rescan"]')).not.toBeNull();
  });

  it('shows no reason line when a failed scan recorded none', () => {
    const el = render(ScanStatus.Failed);

    expect(el.querySelector('[data-testid="scan-section-status"]')?.textContent).toContain('Failed');
    expect(el.querySelector('[data-testid="scan-failure-reason"]')).toBeNull();
  });

  it('shows no reason for a scan that did not fail', () => {
    const el = render(ScanStatus.Completed, 'left over');

    expect(el.querySelector('[data-testid="scan-failure-reason"]')).toBeNull();
  });

  it('cuts an overlong reason and shows only its first line', () => {
    const el = render(ScanStatus.Failed, 'z'.repeat(400) + '\nat internal.Class(File.java:1)');

    const text = el.querySelector('[data-testid="scan-failure-reason-text"]')?.textContent ?? '';
    expect(text.length).toBeLessThanOrEqual(200);
    expect(text).not.toContain('internal.Class');
  });
});
