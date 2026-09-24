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

import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';

import {
  PagedModelVulnerabilityScanInfo,
  RepoType,
  ScanStatus,
  VulnerabilityScanInfo,
} from '../../../../generated/api';
import { ToastService } from '../../shared/components/toast/toast.service';
import { SecurityScanSupportService } from '../../shared/service/security-scan-support.service';
import { legacyNavigationUrl } from '../../shared/util/security-detail-route.testing';
import { buildArtifactDetailRoute } from '../../shared/util/security-detail-route.util';
import { SecurityComponent } from './security.component';
import { SecurityService } from './service/security.service';

describe('SecurityComponent', () => {
  let securityService: jasmine.SpyObj<SecurityService>;
  let supportService: jasmine.SpyObj<SecurityScanSupportService>;
  let component: SecurityComponent;

  beforeEach(() => {
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['listScans', 'getScansSummary']);
    supportService = jasmine.createSpyObj<SecurityScanSupportService>('SecurityScanSupportService', [
      'getSupportedRepoTypes',
    ]);
    securityService.listScans.and.returnValue(
      of({ content: [], page: { totalPages: 0 } } as PagedModelVulnerabilityScanInfo),
    );
    securityService.getScansSummary.and.returnValue(of({ totalCount: 0 }));
    supportService.getSupportedRepoTypes.and.returnValue(of(new Set(['PYPI', 'MAVEN', 'NPM', 'DOCKER'])));

    component = new SecurityComponent(
      securityService,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
      jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']),
      supportService,
    );
  });

  describe('repository type options', () => {
    it('lists ALL first and the supported repository types in alphabetical order', () => {
      component.ngOnInit();

      expect(component.repoTypeOptions).toEqual(['ALL', 'DOCKER', 'MAVEN', 'NPM', 'PYPI']);
    });

    it('keeps ALL first even when a supported type sorts before it', () => {
      supportService.getSupportedRepoTypes.and.returnValue(of(new Set(['CARGO', 'ACME'])));

      component.ngOnInit();

      expect(component.repoTypeOptions).toEqual(['ALL', 'ACME', 'CARGO']);
    });

    it('offers only ALL when no type is supported', () => {
      supportService.getSupportedRepoTypes.and.returnValue(of(new Set<string>()));

      component.ngOnInit();

      expect(component.repoTypeOptions).toEqual(['ALL']);
    });
  });

  describe('search', () => {
    it('refresh empties the search text and the filters and loads the first page again', () => {
      component.search('my-repo');
      component.filterBySeverity('HIGH');
      component.loadPage(2);
      securityService.listScans.calls.reset();

      component.refreshPage();

      expect(component.repoNameSearch).toBe('');
      expect(component.pageNum).toBe(0);
      expect(component.severityOption).toBe('ALL');
      expect(securityService.listScans).toHaveBeenCalledTimes(1);
    });
  });
});

describe('SecurityComponent search box', () => {
  it('is emptied by the refresh button together with the query', () => {
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['listScans', 'getScansSummary']);
    securityService.listScans.and.returnValue(
      of({ content: [], page: { totalPages: 0 } } as PagedModelVulnerabilityScanInfo),
    );
    securityService.getScansSummary.and.returnValue(of({ totalCount: 0 }));
    TestBed.configureTestingModule({
      imports: [SecurityComponent],
      providers: [
        provideRouter([]),
        { provide: SecurityService, useValue: securityService },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
        {
          provide: SecurityScanSupportService,
          useValue: { getSupportedRepoTypes: () => of(new Set(['MAVEN'])) },
        },
      ],
    });
    const fixture = TestBed.createComponent(SecurityComponent);
    fixture.detectChanges();
    const box: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="security-search"] input');

    box.value = 'my-repo';
    box.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(box.value).toBe('my-repo');
    expect(securityService.listScans.calls.mostRecent().args).toContain('my-repo');

    fixture.nativeElement.querySelector('[data-testid="security-refresh"]').click();
    fixture.detectChanges();

    expect(box.value).toBe('');
    expect(securityService.listScans.calls.mostRecent().args).not.toContain('my-repo');
  });
});

describe('SecurityComponent scan rows', () => {
  let router: Router;
  let fixture: ComponentFixture<SecurityComponent>;

  @Component({ template: '' })
  class BlankPage {}

  function scan(id: string, repoType: RepoType, artifactName: string, artifactVersion: string): VulnerabilityScanInfo {
    return {
      id,
      repoName: 'repo',
      repoType,
      artifactName,
      artifactVersion,
      status: ScanStatus.Completed,
    } as VulnerabilityScanInfo;
  }

  function render(scans: VulnerabilityScanInfo[]): HTMLElement {
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['listScans', 'getScansSummary']);
    securityService.listScans.and.returnValue(
      of({ content: scans, page: { totalPages: 1 } } as PagedModelVulnerabilityScanInfo),
    );
    securityService.getScansSummary.and.returnValue(of({ totalCount: scans.length }));
    TestBed.configureTestingModule({
      imports: [SecurityComponent],
      providers: [
        provideRouter([{ path: '**', component: BlankPage }]),
        { provide: SecurityService, useValue: securityService },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
        { provide: SecurityScanSupportService, useValue: { getSupportedRepoTypes: () => of(new Set(['MAVEN'])) } },
      ],
    });
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(SecurityComponent);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  const CASES: [RepoType, string, string, string][] = [
    [RepoType.Maven, 'org.acme:lib', '1.0.0', '/repo/org.acme/lib/1.0.0#security'],
    [RepoType.Npm, '@acme/ui', '1.0.0', '/repo/acme/ui/1.0.0#security'],
    [RepoType.Npm, 'left-pad', '1.0.0', '/repo/~/left-pad/1.0.0#security'],
    [RepoType.Cargo, 'my crate', '1.0.0', '/repo/my%20crate/1.0.0#security'],
    [RepoType.Docker, 'library/nginx', '1.25', '/repo/library/nginx/1.25/detail#security'],
    [
      RepoType.Golang,
      'github.com/acme/lib',
      'v1.0.0',
      '/repo/modules/version?modulePath=github.com%2Facme%2Flib&version=v1.0.0#security',
    ],
  ];

  CASES.forEach(([repoType, name, version, expected]) => {
    it(`links a ${repoType} row for ${name} to the exact URL the row navigated to before`, () => {
      const page = render([scan('7', repoType, name, version)]);

      const row = page.querySelector('[data-testid="security-scan-row-7"]');
      const link = row?.querySelector<HTMLAnchorElement>('a.row-link');
      expect(row?.getAttribute('role')).toBeNull();
      expect(row?.classList).toContain('row-link-host');
      expect(link?.getAttribute('href')).toBe(expected);
      expect(link?.getAttribute('href')).toBe(
        legacyNavigationUrl(router, buildArtifactDetailRoute(repoType, 'repo', name, version)!),
      );
    });
  });

  it('gives a row without a detail page no link at all', () => {
    const page = render([scan('8', RepoType.Docker, 'nginx', 'sha256:abc')]);

    expect(page.querySelector('[data-testid="security-scan-row-8"]')).not.toBeNull();
    expect(page.querySelector('a.row-link')).toBeNull();
  });

  it('opens the version on its security tab on a click', async () => {
    const page = render([scan('9', RepoType.Maven, 'org.acme:lib', '1.0.0')]);

    page.querySelector<HTMLAnchorElement>('a.row-link')!.click();
    await fixture.whenStable();

    expect(router.url).toBe('/repo/org.acme/lib/1.0.0#security');
  });
});
