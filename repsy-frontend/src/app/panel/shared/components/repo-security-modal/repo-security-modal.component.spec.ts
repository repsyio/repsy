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
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';

import { RecentScannedVersion, RepoSecurityDetail } from '../../../../../generated/api';
import { SecurityService } from '../../../pages/security/service/security.service';
import { toApiRepoType } from '../../util/repo-api-type';
import { legacyNavigationUrl } from '../../util/security-detail-route.testing';
import { buildArtifactDetailRoute } from '../../util/security-detail-route.util';
import { RepoSecurityModalComponent } from './repo-security-modal.component';

const DETAIL = { totalCount: 3 } as unknown as RepoSecurityDetail;

describe('RepoSecurityModalComponent', () => {
  let component: RepoSecurityModalComponent;
  let securityService: jasmine.SpyObj<SecurityService>;

  beforeEach(() => {
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['getRepoSecurityDetail']);
    securityService.getRepoSecurityDetail.and.returnValue(of(DETAIL));
    component = new RepoSecurityModalComponent(securityService);
    component.repoName = 'repo';
    component.repoType = 'npm';
  });

  /** What Angular does when the parent flips the `open` input. */
  function setOpen(open: boolean): void {
    component.open = open;
    component.ngOnChanges({ open: new SimpleChange(!open, open, false) });
  }

  describe('opening', () => {
    it('loads the security detail of the repository once it opens', () => {
      setOpen(true);

      expect(securityService.getRepoSecurityDetail).toHaveBeenCalledOnceWith('repo');
      expect(component.detail).toBe(DETAIL);
      expect(component.loading).toBeFalse();
    });

    it('shows the loading state, with no stale detail, while the detail is fetched', () => {
      setOpen(true);
      const pending = new Subject<RepoSecurityDetail>();
      securityService.getRepoSecurityDetail.and.returnValue(pending);

      setOpen(false);
      setOpen(true);

      expect(component.loading).toBeTrue();
      expect(component.detail).toBeNull();

      pending.next(DETAIL);
      pending.complete();

      expect(component.loading).toBeFalse();
      expect(component.detail).toBe(DETAIL);
    });

    it('does not load anything when it closes, or without a repository', () => {
      setOpen(false);
      component.repoName = '';
      setOpen(true);

      expect(securityService.getRepoSecurityDetail).not.toHaveBeenCalled();
    });

    it('stops loading and shows no detail when the request fails', () => {
      securityService.getRepoSecurityDetail.and.returnValue(throwError(() => new Error('boom')));

      setOpen(true);

      expect(component.loading).toBeFalse();
      expect(component.detail).toBeNull();
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
    const scoped = { artifactName: '@acme/ui', artifactVersion: '1.0.0' } as RecentScannedVersion;

    it('lead to their detail page when it is known', () => {
      expect(component.recentScanLink(scoped)).toEqual({ path: '/repo/acme/ui/1.0.0' });
    });

    it('have no link when it is not', () => {
      expect(component.recentScanLink({ artifactName: '@broken', artifactVersion: '1.0.0' })).toBeNull();
      expect(component.recentScanLink({ artifactName: 'ui' })).toBeNull();
    });

    it('build the link once, so that a template binding sees a stable object', () => {
      component.repoType = 'golang';
      const scan = { artifactName: 'github.com/acme/lib', artifactVersion: 'v1.0.0' };

      expect(component.recentScanLink(scan)).toBe(component.recentScanLink(scan));
    });
  });
});

describe('RepoSecurityModalComponent recent scan rows', () => {
  @Component({ template: '' })
  class BlankPage {}

  let router: Router;
  let fixture: ComponentFixture<RepoSecurityModalComponent>;

  function render(repoType: string, scans: RecentScannedVersion[]): HTMLElement {
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['getRepoSecurityDetail']);
    securityService.getRepoSecurityDetail.and.returnValue(
      of({ totalCount: scans.length, recentScans: scans } as unknown as RepoSecurityDetail),
    );
    TestBed.configureTestingModule({
      imports: [RepoSecurityModalComponent],
      providers: [
        provideRouter([{ path: '**', component: BlankPage }]),
        { provide: SecurityService, useValue: securityService },
      ],
    });
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(RepoSecurityModalComponent);
    fixture.componentRef.setInput('repoName', 'repo');
    fixture.componentRef.setInput('repoType', repoType);
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  const CASES: [string, string, string, string][] = [
    ['maven', 'org.acme:lib', '1.0.0', '/repo/org.acme/lib/1.0.0#security'],
    ['npm', '@acme/ui', '1.0.0', '/repo/acme/ui/1.0.0#security'],
    ['npm', 'left-pad', '1.0.0', '/repo/~/left-pad/1.0.0#security'],
    ['cargo', 'my crate', '1.0.0', '/repo/my%20crate/1.0.0#security'],
    ['docker', 'library/nginx', '1.25', '/repo/library/nginx/1.25/detail#security'],
    [
      'golang',
      'github.com/acme/lib',
      'v1.0.0',
      '/repo/modules/version?modulePath=github.com%2Facme%2Flib&version=v1.0.0#security',
    ],
  ];

  CASES.forEach(([repoType, name, version, expected]) => {
    it(`links a ${repoType} row for ${name} to the exact URL the row navigated to before`, () => {
      const dialog = render(repoType, [{ artifactName: name, artifactVersion: version } as RecentScannedVersion]);

      const row = dialog.querySelector('[data-testid^="security-recent-scan-"]');
      const link = row?.querySelector<HTMLAnchorElement>('a.row-link');
      expect(row?.getAttribute('role')).toBeNull();
      expect(row?.classList).toContain('row-link-host');
      expect(link?.getAttribute('href')).toBe(expected);
      expect(link?.getAttribute('href')).toBe(
        legacyNavigationUrl(router, buildArtifactDetailRoute(toApiRepoType(repoType), 'repo', name, version)!),
      );
    });
  });

  it('gives a row without a detail page no link at all', () => {
    const dialog = render('docker', [{ artifactName: 'nginx', artifactVersion: 'sha256:abc' } as RecentScannedVersion]);

    expect(dialog.querySelector('[data-testid^="security-recent-scan-"]')).not.toBeNull();
    expect(dialog.querySelector('a.row-link')).toBeNull();
  });

  it('opens the version on its security tab and closes the modal on a click', async () => {
    const dialog = render('maven', [
      { artifactName: 'org.acme:lib', artifactVersion: '1.0.0' } as RecentScannedVersion,
    ]);
    const closed: boolean[] = [];
    fixture.componentInstance.openChange.subscribe((value) => closed.push(value));

    dialog.querySelector<HTMLAnchorElement>('a.row-link')!.click();
    await fixture.whenStable();

    expect(router.url).toBe('/repo/org.acme/lib/1.0.0#security');
    expect(closed).toEqual([false]);
  });
});
