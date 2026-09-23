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

import { SimpleChange } from '@angular/core';
import { Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';

import { ScanOverview, ScanStatus, VulnerabilityScanControllerService } from '../../../../../generated/api';
import { VersionSecurityModalComponent } from './version-security-modal.component';

const OVERVIEW = { status: ScanStatus.Completed, lastCompletedAt: '2026-01-01T00:00:00Z' } as ScanOverview;

describe('VersionSecurityModalComponent', () => {
  let component: VersionSecurityModalComponent;
  let scanService: jasmine.SpyObj<VulnerabilityScanControllerService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    scanService = jasmine.createSpyObj<VulnerabilityScanControllerService>('VulnerabilityScanControllerService', [
      'getScanOverview',
    ]);
    scanService.getScanOverview.and.returnValue(of({ data: OVERVIEW }) as never);
    router = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']);
    component = new VersionSecurityModalComponent(scanService, router);
    component.repoName = 'repo';
    component.repoType = 'maven';
    component.artifactName = 'org.acme:lib';
    component.artifactVersion = '1.0.0';
  });

  /** What Angular does when the parent flips the `open` input. */
  function setOpen(open: boolean): void {
    component.open = open;
    component.ngOnChanges({ open: new SimpleChange(!open, open, false) });
  }

  describe('opening', () => {
    it('loads the scan overview of the version once it opens', () => {
      setOpen(true);

      expect(scanService.getScanOverview).toHaveBeenCalledOnceWith('org.acme:lib', '1.0.0', 'repo');
      expect(component.overview).toBe(OVERVIEW);
      expect(component.loading).toBeFalse();
    });

    it('shows the loading state, with no stale overview, while it is fetched', () => {
      setOpen(true);
      const pending = new Subject<{ data: ScanOverview }>();
      scanService.getScanOverview.and.returnValue(pending as never);

      setOpen(false);
      setOpen(true);

      expect(component.loading).toBeTrue();
      expect(component.overview).toBeNull();

      pending.next({ data: OVERVIEW });
      pending.complete();

      expect(component.loading).toBeFalse();
      expect(component.overview).toBe(OVERVIEW);
    });

    it('has no overview when the response carries no data', () => {
      scanService.getScanOverview.and.returnValue(of({}) as never);

      setOpen(true);

      expect(component.overview).toBeNull();
    });

    it('does not load anything when it closes, or without a repository, artifact or version', () => {
      setOpen(false);
      for (const missing of ['repoName', 'artifactName', 'artifactVersion'] as const) {
        const value = component[missing];
        component[missing] = '';
        setOpen(true);
        component[missing] = value;
      }

      expect(scanService.getScanOverview).not.toHaveBeenCalled();
    });

    it('stops loading and shows no overview when the request fails', () => {
      scanService.getScanOverview.and.returnValue(throwError(() => new Error('boom')));

      setOpen(true);

      expect(component.loading).toBeFalse();
      expect(component.overview).toBeNull();
    });
  });

  describe('closeModal', () => {
    it('asks the parent to close it', () => {
      const emitted: boolean[] = [];
      component.openChange.subscribe((value) => emitted.push(value));

      component.closeModal();

      expect(emitted).toEqual([false]);
    });
  });

  describe('hasRescanNote', () => {
    it('is false before the overview is loaded', () => {
      expect(component.hasRescanNote).toBeFalse();
    });

    it('is false when the newest scan completed', () => {
      setOpen(true);

      expect(component.hasRescanNote).toBeFalse();
    });

    it('is true while a rescan is unfinished or after it failed', () => {
      for (const status of [ScanStatus.Pending, ScanStatus.Queued, ScanStatus.Running, ScanStatus.Failed]) {
        scanService.getScanOverview.and.returnValue(of({ data: { ...OVERVIEW, status } }) as never);

        setOpen(true);

        expect(component.hasRescanNote).withContext(status).toBeTrue();
      }
    });
  });

  describe('the detail page', () => {
    it('is clickable when the route of the version is known', () => {
      expect(component.isDetailClickable).toBeTrue();
    });

    it('is not clickable when it is not, such as a Docker digest', () => {
      component.repoType = 'docker';
      component.artifactVersion = 'sha256:abc';

      expect(component.isDetailClickable).toBeFalse();
    });

    it('opens on its security tab and closes the modal', () => {
      const closed: boolean[] = [];
      component.openChange.subscribe((value) => closed.push(value));
      const event = jasmine.createSpyObj<Event>('Event', ['stopPropagation']);

      component.openDetail(event);

      expect(event.stopPropagation).toHaveBeenCalled();
      expect(closed).toEqual([false]);
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/repo/org.acme/lib/1.0.0#security');
    });

    it('opens through navigate when the route has query parameters', () => {
      component.repoType = 'golang';
      component.artifactName = 'github.com/acme/lib';
      component.artifactVersion = 'v1.0.0';

      component.openDetail(jasmine.createSpyObj<Event>('Event', ['stopPropagation']));

      expect(router.navigate).toHaveBeenCalledOnceWith(['/repo/modules/version'], {
        queryParams: { modulePath: 'github.com/acme/lib', version: 'v1.0.0' },
        fragment: 'security',
      });
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('does nothing, not even close the modal, when the route is not known', () => {
      component.repoType = 'docker';
      component.artifactVersion = 'sha256:abc';
      const closed: boolean[] = [];
      component.openChange.subscribe((value) => closed.push(value));
      const event = jasmine.createSpyObj<Event>('Event', ['stopPropagation']);

      component.openDetail(event);

      expect(event.stopPropagation).toHaveBeenCalled();
      expect(closed).toEqual([]);
      expect(router.navigate).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });
  });
});
