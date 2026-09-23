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
import { NEVER, of, Subject, throwError } from 'rxjs';

import { RecentScannedVersion, RepoSecurityDetail } from '../../../../../generated/api';
import { SecurityService } from '../../../pages/security/service/security.service';
import { PackageSecurityModalComponent } from './package-security-modal.component';

const DETAIL = { totalCount: 3 } as unknown as RepoSecurityDetail;

describe('PackageSecurityModalComponent', () => {
  let component: PackageSecurityModalComponent;
  let securityService: jasmine.SpyObj<SecurityService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['getArtifactSecurityDetail']);
    securityService.getArtifactSecurityDetail.and.returnValue(of(DETAIL));
    router = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']);
    component = new PackageSecurityModalComponent(securityService, router);
    component.repoName = 'repo';
    component.repoType = 'maven';
    component.artifactName = 'org.acme:lib';
    component.packageRoute = '/repo/org.acme/lib';
  });

  /** What Angular does when the parent flips the `open` input. */
  function setOpen(open: boolean): void {
    component.open = open;
    component.ngOnChanges({ open: new SimpleChange(!open, open, false) });
  }

  describe('opening', () => {
    it('loads the security detail of the artifact once it opens', () => {
      setOpen(true);

      expect(securityService.getArtifactSecurityDetail).toHaveBeenCalledOnceWith('repo', 'org.acme:lib');
      expect(component.detail).toBe(DETAIL);
      expect(component.loading).toBeFalse();
    });

    it('shows the loading state, with no stale detail, while the detail is fetched', () => {
      setOpen(true);
      const pending = new Subject<RepoSecurityDetail>();
      securityService.getArtifactSecurityDetail.and.returnValue(pending);

      setOpen(false);
      setOpen(true);

      expect(component.loading).toBeTrue();
      expect(component.detail).toBeNull();

      pending.next(DETAIL);
      pending.complete();

      expect(component.loading).toBeFalse();
      expect(component.detail).toBe(DETAIL);
    });

    it('does not load anything when it closes', () => {
      setOpen(false);

      expect(securityService.getArtifactSecurityDetail).not.toHaveBeenCalled();
    });

    it('does not load anything for an input change other than open', () => {
      component.open = true;

      component.ngOnChanges({ artifactName: new SimpleChange('a', 'b', false) });

      expect(securityService.getArtifactSecurityDetail).not.toHaveBeenCalled();
    });

    it('does not load anything without a repository or an artifact', () => {
      component.artifactName = '';
      setOpen(true);
      component.artifactName = 'org.acme:lib';
      component.repoName = '';
      setOpen(true);

      expect(securityService.getArtifactSecurityDetail).not.toHaveBeenCalled();
    });

    it('stops loading and shows no detail when the request fails', () => {
      securityService.getArtifactSecurityDetail.and.returnValue(throwError(() => new Error('boom')));

      setOpen(true);

      expect(component.loading).toBeFalse();
      expect(component.detail).toBeNull();
    });

    it('is still loading while the request is pending', () => {
      securityService.getArtifactSecurityDetail.and.returnValue(NEVER);

      setOpen(true);

      expect(component.loading).toBeTrue();
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

  describe('recent scans', () => {
    const mavenScan = { artifactName: 'org.acme:lib', artifactVersion: '1.0.0' } as RecentScannedVersion;

    it('are clickable when their detail page is known', () => {
      expect(component.isRecentScanClickable(mavenScan)).toBeTrue();
    });

    it('are not clickable when it is not, such as a Docker digest', () => {
      component.repoType = 'docker';

      expect(component.isRecentScanClickable({ artifactName: 'nginx', artifactVersion: 'sha256:abc' })).toBeFalse();
    });

    it('open the version detail on its security tab and close the modal', () => {
      const closed: boolean[] = [];
      component.openChange.subscribe((value) => closed.push(value));
      const event = jasmine.createSpyObj<Event>('Event', ['stopPropagation']);

      component.openRecentScan(mavenScan, event);

      expect(event.stopPropagation).toHaveBeenCalled();
      expect(closed).toEqual([false]);
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/repo/org.acme/lib/1.0.0#security');
    });

    it('open a route with query parameters through navigate', () => {
      component.repoType = 'golang';

      component.openRecentScan(
        { artifactName: 'github.com/acme/lib', artifactVersion: 'v1.0.0' },
        jasmine.createSpyObj<Event>('Event', ['stopPropagation']),
      );

      expect(router.navigate).toHaveBeenCalledOnceWith(['/repo/modules/version'], {
        queryParams: { modulePath: 'github.com/acme/lib', version: 'v1.0.0' },
        fragment: 'security',
      });
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });

    it('do nothing, not even close the modal, when the version has no detail page', () => {
      component.repoType = 'docker';
      const closed: boolean[] = [];
      component.openChange.subscribe((value) => closed.push(value));
      const event = jasmine.createSpyObj<Event>('Event', ['stopPropagation']);

      component.openRecentScan({ artifactName: 'nginx', artifactVersion: 'sha256:abc' }, event);

      expect(event.stopPropagation).toHaveBeenCalled();
      expect(closed).toEqual([]);
      expect(router.navigate).not.toHaveBeenCalled();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });
  });

  describe('viewAllVersions', () => {
    it('closes the modal and goes to the package', () => {
      const closed: boolean[] = [];
      component.openChange.subscribe((value) => closed.push(value));
      const event = jasmine.createSpyObj<Event>('Event', ['stopPropagation']);

      component.viewAllVersions(event);

      expect(event.stopPropagation).toHaveBeenCalled();
      expect(closed).toEqual([false]);
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/repo/org.acme/lib');
    });

    it('keeps the query parameters of the package route when it has any', () => {
      component.packageRoute = '/repo/modules/version';
      component.packageQueryParams = { modulePath: 'github.com/acme/lib' };

      component.viewAllVersions(jasmine.createSpyObj<Event>('Event', ['stopPropagation']));

      expect(router.navigate).toHaveBeenCalledOnceWith(['/repo/modules/version'], {
        queryParams: { modulePath: 'github.com/acme/lib' },
      });
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });
  });
});
