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
import { SimpleChange } from '@angular/core';
import { fakeAsync, tick } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';

import {
  ScanOverview,
  ScanStatus,
  Severity,
  VulnerabilityFindingInfo,
  VulnerabilityScanControllerService,
  VulnerabilityScanDetail,
  VulnerabilityScanInfo,
} from '../../../../../generated/api';
import { restResponse } from '../../../pages/repository/testing/protocol-service-spec-helpers';
import { SecurityScanSupportService } from '../../service/security-scan-support.service';
import { ToastService } from '../toast/toast.service';
import { SecurityScanSectionComponent } from './security-scan-section.component';

const REPO = 'acme-repo';
const ARTIFACT = 'acme-artifact';
const VERSION = '1.0.0';
const POLL_INTERVAL_MS = 3000;

/** A successful `RestResponse*` reply; the generated client's overloads make a typed spy return value unusable. */
const reply = (data: unknown): never => of(restResponse(data)) as never;

function scanInfo(id: string, status: ScanStatus = ScanStatus.Completed): VulnerabilityScanInfo {
  return { id, status, repoName: REPO };
}

function scanDetail(
  id: string,
  status: ScanStatus,
  extra: Partial<VulnerabilityScanDetail> = {},
): VulnerabilityScanDetail {
  return { id, status, repoName: REPO, ...extra };
}

function finding(id: string, severity: Severity): VulnerabilityFindingInfo {
  return { id, severity, cveId: `CVE-${id}` };
}

function overviewOf(scanId: string, status: ScanStatus, counts: Partial<ScanOverview> = {}): ScanOverview {
  return { scanId, status, ...counts };
}

describe('SecurityScanSectionComponent', () => {
  let component: SecurityScanSectionComponent;
  let api: jasmine.SpyObj<VulnerabilityScanControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let supportService: jasmine.SpyObj<SecurityScanSupportService>;
  let scroller: jasmine.SpyObj<ViewportScroller>;
  let fragment$: BehaviorSubject<string | null>;
  let snapshot: { fragment: string | null };

  /** What the mocked backend currently answers; tests change these to script a scan's life cycle. */
  let scans: VulnerabilityScanInfo[];
  let overview: ScanOverview;
  let details: Record<string, VulnerabilityScanDetail>;
  let findings: VulnerabilityFindingInfo[];

  beforeEach(() => {
    scans = [scanInfo('scan-2'), scanInfo('scan-1')];
    overview = overviewOf('scan-2', ScanStatus.Completed, { criticalCount: 1, highCount: 2 });
    details = {
      'scan-1': scanDetail('scan-1', ScanStatus.Completed),
      'scan-2': scanDetail('scan-2', ScanStatus.Completed),
    };
    findings = [finding('a', Severity.Critical), finding('b', Severity.High), finding('c', Severity.High)];

    api = jasmine.createSpyObj<VulnerabilityScanControllerService>('VulnerabilityScanControllerService', [
      'listVulnerabilityScans',
      'getScanOverview',
      'getVulnerabilityScan',
      'getVulnerabilityScanFindings',
      'triggerVulnerabilityScan',
    ]);
    api.listVulnerabilityScans.and.callFake(() => reply({ content: scans, page: { totalPages: 3 } }));
    api.getScanOverview.and.callFake(() => reply(overview));
    api.getVulnerabilityScan.and.callFake(((scanId: string) => reply(details[scanId])) as never);
    api.getVulnerabilityScanFindings.and.callFake(() =>
      reply({ content: findings, page: { totalPages: 2, totalElements: 14 } }),
    );
    api.triggerVulnerabilityScan.and.returnValue(reply(undefined));

    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    supportService = jasmine.createSpyObj<SecurityScanSupportService>('SecurityScanSupportService', ['isSupported']);
    supportService.isSupported.and.returnValue(of(true));
    scroller = jasmine.createSpyObj<ViewportScroller>('ViewportScroller', ['scrollToAnchor']);
    fragment$ = new BehaviorSubject<string | null>(null);
    snapshot = { fragment: null };

    component = new SecurityScanSectionComponent(
      api,
      toastService,
      { fragment: fragment$.asObservable(), snapshot } as unknown as ActivatedRoute,
      supportService,
      scroller,
    );
    component.repoType = 'npm';
    component.repoName = REPO;
    component.artifactName = ARTIFACT;
    component.artifactVersion = VERSION;
  });

  afterEach(() => component.ngOnDestroy());

  /** Delivers the artifact coordinates the way the parent's input bindings do, which loads the scans. */
  function bind(changes: string[] = ['repoName', 'artifactName', 'artifactVersion']): void {
    component.ngOnChanges(Object.fromEntries(changes.map((name) => [name, new SimpleChange(null, 'x', true)])));
  }

  describe('loading the scans', () => {
    it('does nothing until the coordinates change and are all known', () => {
      bind(['canTriggerScan']);
      component.artifactVersion = '';
      bind(['artifactVersion']);

      expect(api.listVulnerabilityScans).not.toHaveBeenCalled();
      expect(component.loading).toBeTrue();
    });

    it('loads the first page of scans, five at a time, for the artifact', () => {
      component.pageNum = 2;

      bind();

      expect(api.listVulnerabilityScans).toHaveBeenCalledOnceWith(ARTIFACT, VERSION, REPO, 0, 5);
      expect(component.scans).toEqual(scans);
      expect(component.totalPages).toBe(3);
      expect(component.neverScanned).toBeFalse();
      expect(component.loading).toBeFalse();
    });

    it('loads the overview, the newest scan and its findings, sorted by severity', () => {
      bind();

      expect(api.getScanOverview).toHaveBeenCalledOnceWith(ARTIFACT, VERSION, REPO);
      expect(component.overview).toEqual(overview);
      expect(api.getVulnerabilityScan).toHaveBeenCalledOnceWith('scan-2', REPO);
      expect(component.selectedScan).toEqual(details['scan-2']);
      expect(api.getVulnerabilityScanFindings).toHaveBeenCalledOnceWith('scan-2', REPO, 0, 10, ['severity,ASC']);
      expect(component.findings).toEqual(findings);
      expect(component.findingsTotalPages).toBe(2);
      expect(component.findingsTotalCount).toBe(14);
      expect(component.loadingFindings).toBeFalse();
    });

    it('counts the loaded findings when the page carries no total', () => {
      api.getVulnerabilityScanFindings.and.callFake(() => reply({ content: findings, page: { totalPages: 1 } }));

      bind();

      expect(component.findingsTotalCount).toBe(3);
    });

    it('marks an artifact without any scan as never scanned and skips the overview', () => {
      scans = [];

      bind();

      expect(component.neverScanned).toBeTrue();
      expect(component.scans).toEqual([]);
      expect(api.getScanOverview).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });

    it('tolerates a response without data', () => {
      api.listVulnerabilityScans.and.callFake(() => reply(undefined));

      bind();

      expect(component.scans).toEqual([]);
      expect(component.totalPages).toBe(0);
      expect(component.neverScanned).toBeTrue();
    });

    it('stops loading and keeps quiet when the scan list fails, leaving the error to the interceptor', () => {
      api.listVulnerabilityScans.and.returnValue(throwError(() => new Error('boom')));

      bind();

      expect(component.loading).toBeFalse();
      expect(toastService.show).not.toHaveBeenCalled();
    });

    it('starts over from the first page when the artifact version changes', () => {
      bind();
      component.pageNum = 2;
      component.artifactVersion = '2.0.0';

      component.ngOnChanges({ artifactVersion: new SimpleChange(VERSION, '2.0.0', false) });

      expect(api.listVulnerabilityScans.calls.mostRecent().args).toEqual([ARTIFACT, '2.0.0', REPO, 0, 5]);
      expect(component.pageNum).toBe(0);
    });

    it('loadPage fetches only the requested page of scans', () => {
      bind();
      api.listVulnerabilityScans.calls.reset();
      api.getScanOverview.calls.reset();

      component.loadPage(2);

      expect(api.listVulnerabilityScans).toHaveBeenCalledOnceWith(ARTIFACT, VERSION, REPO, 2, 5);
      expect(api.getScanOverview).not.toHaveBeenCalled();
      expect(component.pageNum).toBe(2);
    });
  });

  describe('findings', () => {
    beforeEach(() => bind());

    it('selectScan shows that scan and starts its findings from the first page', () => {
      component.findingsPageNum = 4;

      component.selectScan('scan-1');

      expect(component.selectedScan).toEqual(details['scan-1']);
      expect(api.getVulnerabilityScanFindings.calls.mostRecent().args).toEqual([
        'scan-1',
        REPO,
        0,
        10,
        ['severity,ASC'],
      ]);
    });

    it('loadFindingsPage loads the requested page of the selected scan', () => {
      component.loadFindingsPage(3);

      expect(component.findingsPageNum).toBe(3);
      expect(api.getVulnerabilityScanFindings.calls.mostRecent().args).toEqual([
        'scan-2',
        REPO,
        3,
        10,
        ['severity,ASC'],
      ]);
    });

    it('loadFindingsPage without a selected scan only records the page', () => {
      component.selectedScan = null;
      api.getVulnerabilityScanFindings.calls.reset();

      component.loadFindingsPage(3);

      expect(component.findingsPageNum).toBe(3);
      expect(api.getVulnerabilityScanFindings).not.toHaveBeenCalled();
    });

    it('toggleFindingsSort flips the direction, returns to the first page and reloads', () => {
      component.findingsPageNum = 3;

      component.toggleFindingsSort();

      expect(component.findingsSortDirection).toBe('DESC');
      expect(component.findingsPageNum).toBe(0);
      expect(api.getVulnerabilityScanFindings.calls.mostRecent().args).toEqual([
        'scan-2',
        REPO,
        0,
        10,
        ['severity,DESC'],
      ]);

      component.toggleFindingsSort();

      expect(component.findingsSortDirection).toBe('ASC');
      expect(api.getVulnerabilityScanFindings.calls.mostRecent().args).toEqual([
        'scan-2',
        REPO,
        0,
        10,
        ['severity,ASC'],
      ]);
    });

    it('toggleFindingsSort without a selected scan only flips the direction', () => {
      component.selectedScan = null;
      api.getVulnerabilityScanFindings.calls.reset();

      component.toggleFindingsSort();

      expect(component.findingsSortDirection).toBe('DESC');
      expect(api.getVulnerabilityScanFindings).not.toHaveBeenCalled();
    });

    it('keeps quiet and stops the findings spinner when loading them fails', () => {
      api.getVulnerabilityScanFindings.and.returnValue(throwError(() => new Error('boom')));

      component.loadFindingsPage(1);

      expect(component.loadingFindings).toBeFalse();
      expect(toastService.show).not.toHaveBeenCalled();
    });
  });

  describe('worstSeverity', () => {
    it('is null without an overview', () => {
      component.overview = null;

      expect(component.worstSeverity).toBeNull();
    });

    it('is the highest severity with at least one finding', () => {
      const worst = (counts: Partial<ScanOverview>): Severity | null => {
        component.overview = overviewOf('scan-1', ScanStatus.Completed, counts);
        return component.worstSeverity;
      };

      expect(worst({ criticalCount: 1, highCount: 5, mediumCount: 5, lowCount: 5, unknownCount: 5 })).toBe(
        Severity.Critical,
      );
      expect(worst({ criticalCount: 0, highCount: 1, mediumCount: 5, lowCount: 5, unknownCount: 5 })).toBe(
        Severity.High,
      );
      expect(worst({ highCount: 0, mediumCount: 1, lowCount: 5, unknownCount: 5 })).toBe(Severity.Medium);
      expect(worst({ mediumCount: 0, lowCount: 1, unknownCount: 5 })).toBe(Severity.Low);
      expect(worst({ lowCount: 0, unknownCount: 1 })).toBe(Severity.Unknown);
    });

    it('is null when no severity has a finding, including counters the API left out', () => {
      component.overview = overviewOf('scan-1', ScanStatus.Completed, {
        criticalCount: 0,
        highCount: 0,
        mediumCount: 0,
        lowCount: 0,
        unknownCount: 0,
      });
      expect(component.worstSeverity).toBeNull();

      component.overview = overviewOf('scan-1', ScanStatus.Completed);
      expect(component.worstSeverity).toBeNull();
    });
  });

  describe('selectedScanSeverityCounts', () => {
    beforeEach(() => {
      component.findings = [
        finding('a', Severity.Critical),
        finding('b', Severity.High),
        finding('c', Severity.High),
        finding('d', Severity.Unknown),
      ];
    });

    it('uses the overview counters while the selected scan is the completed overview scan', () => {
      component.overview = overviewOf('scan-2', ScanStatus.Completed, {
        criticalCount: 7,
        highCount: 6,
        mediumCount: 5,
      });
      component.selectedScan = scanDetail('scan-2', ScanStatus.Completed);

      expect(component.selectedScanSeverityCounts).toEqual({
        criticalCount: 7,
        highCount: 6,
        mediumCount: 5,
        lowCount: 0,
        unknownCount: 0,
      });
    });

    it('counts the loaded findings for any other selected scan', () => {
      component.overview = overviewOf('scan-2', ScanStatus.Completed, { criticalCount: 7 });
      component.selectedScan = scanDetail('scan-1', ScanStatus.Completed);

      expect(component.selectedScanSeverityCounts).toEqual({
        criticalCount: 1,
        highCount: 2,
        mediumCount: 0,
        lowCount: 0,
        unknownCount: 1,
      });
    });

    it('counts the loaded findings while the overview scan has not completed', () => {
      component.overview = overviewOf('scan-2', ScanStatus.Running, { criticalCount: 7 });
      component.selectedScan = scanDetail('scan-2', ScanStatus.Running);

      expect(component.selectedScanSeverityCounts.criticalCount).toBe(1);
    });

    it('counts the loaded findings without an overview', () => {
      component.overview = null;
      component.selectedScan = null;

      expect(component.selectedScanSeverityCounts.highCount).toBe(2);
    });
  });

  describe('canTrigger', () => {
    it('is true for a finished scan or an artifact whose overview has not loaded', () => {
      component.overview = null;
      expect(component.canTrigger).toBeTrue();

      component.overview = overviewOf('scan-1', ScanStatus.Completed);
      expect(component.canTrigger).toBeTrue();

      component.overview = overviewOf('scan-1', ScanStatus.Failed);
      expect(component.canTrigger).toBeTrue();
    });

    it('is false while a scan is pending, queued or running', () => {
      [ScanStatus.Pending, ScanStatus.Queued, ScanStatus.Running].forEach((status) => {
        component.overview = overviewOf('scan-1', status);
        expect(component.canTrigger).withContext(status).toBeFalse();
      });
    });

    it('is false for a never scanned artifact and while a scan is being triggered', () => {
      component.neverScanned = true;
      expect(component.canTrigger).toBeFalse();

      component.neverScanned = false;
      component.triggering = true;
      expect(component.canTrigger).toBeFalse();
    });
  });

  describe('triggerScan', () => {
    beforeEach(() => bind());

    it('starts a scan of the artifact version, toasts and reloads the scans from the first page', () => {
      component.pageNum = 2;
      api.listVulnerabilityScans.calls.reset();

      component.triggerScan();

      expect(api.triggerVulnerabilityScan).toHaveBeenCalledOnceWith(ARTIFACT, VERSION, REPO);
      expect(toastService.show).toHaveBeenCalledOnceWith('Vulnerability scan triggered', 'success');
      expect(api.listVulnerabilityScans).toHaveBeenCalledOnceWith(ARTIFACT, VERSION, REPO, 0, 5);
      expect(component.pageNum).toBe(0);
    });

    it('is busy while the request runs', () => {
      const response = new Subject<unknown>();
      api.triggerVulnerabilityScan.and.returnValue(response as never);

      component.triggerScan();

      expect(component.triggering).toBeTrue();
      expect(component.canTrigger).toBeFalse();

      response.next({});
      response.complete();

      expect(component.triggering).toBeFalse();
    });

    it('does not toast or reload when the request fails, leaving the error to the interceptor', () => {
      api.triggerVulnerabilityScan.and.returnValue(throwError(() => new Error('boom')));
      api.listVulnerabilityScans.calls.reset();

      component.triggerScan();

      expect(toastService.show).not.toHaveBeenCalled();
      expect(api.listVulnerabilityScans).not.toHaveBeenCalled();
      expect(component.triggering).toBeFalse();
    });
  });

  describe('expanding', () => {
    it('toggles between collapsed and expanded', () => {
      expect(component.expanded).toBeFalse();

      component.toggleExpanded();
      expect(component.expanded).toBeTrue();

      component.toggleExpanded();
      expect(component.expanded).toBeFalse();
    });

    it('expands when the route fragment is "security" and ignores other fragments', () => {
      fragment$.next('readme');
      expect(component.expanded).toBeFalse();

      fragment$.next('security');
      expect(component.expanded).toBeTrue();
    });

    it('stops following the route fragment once destroyed', () => {
      component.ngOnDestroy();

      fragment$.next('security');

      expect(component.expanded).toBeFalse();
    });
  });

  describe('scrolling to the section', () => {
    it('scrolls to the security anchor now and again at 300 and 800 ms, as the page settles', fakeAsync(() => {
      snapshot.fragment = 'security';

      component.ngOnInit();

      expect(supportService.isSupported).toHaveBeenCalledOnceWith('npm');
      expect(scroller.scrollToAnchor).not.toHaveBeenCalled();
      tick(0);
      expect(scroller.scrollToAnchor).toHaveBeenCalledTimes(1);
      tick(300);
      expect(scroller.scrollToAnchor).toHaveBeenCalledTimes(2);
      tick(500);
      expect(scroller.scrollToAnchor).toHaveBeenCalledTimes(3);
      expect(scroller.scrollToAnchor.calls.allArgs()).toEqual([['security'], ['security'], ['security']]);
    }));

    it('does not scroll when the repository type does not support scanning', fakeAsync(() => {
      snapshot.fragment = 'security';
      supportService.isSupported.and.returnValue(of(false));

      component.ngOnInit();
      tick(1000);

      expect(scroller.scrollToAnchor).not.toHaveBeenCalled();
    }));

    it('does not scroll when the URL has no security fragment', fakeAsync(() => {
      snapshot.fragment = 'readme';

      component.ngOnInit();
      tick(1000);

      expect(scroller.scrollToAnchor).not.toHaveBeenCalled();
    }));
  });

  describe('polling an unfinished scan', () => {
    function runOverview(): void {
      overview = overviewOf('scan-2', ScanStatus.Running);
      details['scan-2'] = scanDetail('scan-2', ScanStatus.Running);
      scans = [scanInfo('scan-2', ScanStatus.Running), scanInfo('scan-1')];
    }

    function pollCalls(): number {
      return api.getVulnerabilityScan.calls.allArgs().filter(([id]) => id === 'scan-2').length;
    }

    it('does not poll a finished scan', fakeAsync(() => {
      bind();
      tick(10 * POLL_INTERVAL_MS);

      // Only the detail load of the initial refresh.
      expect(pollCalls()).toBe(1);
    }));

    [ScanStatus.Pending, ScanStatus.Queued, ScanStatus.Running].forEach((status) => {
      it(`polls a ${status} scan straight away and then every 3 seconds`, fakeAsync(() => {
        overview = overviewOf('scan-2', status);
        details['scan-2'] = scanDetail('scan-2', status);

        bind();
        expect(pollCalls()).toBe(1);
        tick(0);
        expect(pollCalls()).toBe(2);
        tick(POLL_INTERVAL_MS - 1);
        expect(pollCalls()).toBe(2);
        tick(1);
        expect(pollCalls()).toBe(3);
        tick(POLL_INTERVAL_MS);
        expect(pollCalls()).toBe(4);
        component.ngOnDestroy();
      }));
    });

    it('applies the finished scan to the history, the overview and the selection, then stops polling', fakeAsync(() => {
      runOverview();
      bind();
      tick(0);

      details['scan-2'] = scanDetail('scan-2', ScanStatus.Completed, {
        startedAt: 'started',
        completedAt: 'completed',
        scannerVersion: '1.2.3',
      });
      overview = overviewOf('scan-2', ScanStatus.Completed, { criticalCount: 1, totalCount: 1 });
      api.getScanOverview.calls.reset();
      api.getVulnerabilityScanFindings.calls.reset();
      tick(POLL_INTERVAL_MS);

      expect(component.scans[0]).toEqual(
        jasmine.objectContaining({
          id: 'scan-2',
          status: ScanStatus.Completed,
          startedAt: 'started',
          completedAt: 'completed',
          scannerVersion: '1.2.3',
        }),
      );
      expect(component.scans[1]).toEqual(scanInfo('scan-1'));
      expect(component.selectedScan).toEqual(details['scan-2']);
      expect(component.overview.status).toBe(ScanStatus.Completed);
      expect(component.overview.criticalCount).toBe(1);
      expect(api.getScanOverview).toHaveBeenCalledTimes(1);
      expect(api.getVulnerabilityScanFindings).toHaveBeenCalledTimes(1);
      expect(component.canTrigger).toBeTrue();

      const callsAtCompletion = pollCalls();
      tick(5 * POLL_INTERVAL_MS);
      expect(pollCalls()).toBe(callsAtCompletion);
    }));

    it('also stops on a failed scan', fakeAsync(() => {
      runOverview();
      bind();
      tick(0);

      details['scan-2'] = scanDetail('scan-2', ScanStatus.Failed, { errorMessage: 'scanner crashed' });
      overview = overviewOf('scan-2', ScanStatus.Failed);
      tick(POLL_INTERVAL_MS);

      expect(component.scans[0].errorMessage).toBe('scanner crashed');
      expect(component.selectedScan.status).toBe(ScanStatus.Failed);

      const callsAtCompletion = pollCalls();
      tick(5 * POLL_INTERVAL_MS);
      expect(pollCalls()).toBe(callsAtCompletion);
    }));

    it('leaves another selected scan and its findings alone when the polled scan finishes', fakeAsync(() => {
      runOverview();
      bind();
      tick(0);
      component.selectScan('scan-1');
      api.getVulnerabilityScanFindings.calls.reset();

      details['scan-2'] = scanDetail('scan-2', ScanStatus.Completed);
      overview = overviewOf('scan-2', ScanStatus.Completed, { criticalCount: 3 });
      tick(POLL_INTERVAL_MS);

      expect(component.selectedScan.id).toBe('scan-1');
      expect(api.getVulnerabilityScanFindings).not.toHaveBeenCalled();
      expect(component.scans[0].status).toBe(ScanStatus.Completed);
      expect(component.overview.criticalCount).toBe(3);
    }));

    it('stops polling when the component is destroyed', fakeAsync(() => {
      runOverview();
      bind();
      tick(0);
      const callsBeforeDestroy = pollCalls();

      component.ngOnDestroy();
      tick(5 * POLL_INTERVAL_MS);

      expect(pollCalls()).toBe(callsBeforeDestroy);
    }));

    it('replaces the poller instead of adding one when the artifact version changes', fakeAsync(() => {
      runOverview();
      bind();
      tick(0);
      api.getVulnerabilityScan.calls.reset();

      component.ngOnChanges({ artifactVersion: new SimpleChange(VERSION, '2.0.0', false) });
      tick(0);
      tick(POLL_INTERVAL_MS);

      // The detail load of the new refresh, then one poll at 0 ms and one at 3 s; a leaked first poller would add more.
      expect(pollCalls()).toBe(3);
      component.ngOnDestroy();
    }));
  });
});
