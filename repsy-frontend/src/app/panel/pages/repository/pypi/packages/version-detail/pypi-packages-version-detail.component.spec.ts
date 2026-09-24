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

import { Component, Input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { ReleaseDetail, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/service/breadcrumb-security-link.service';
import { VERSION_PROBE_SORT } from '../../../../../shared/util/version-delete-landing.util';
import { RepoLookupService } from '../../../repo-entry/repo-lookup.service';
import { PypiService } from '../../service/pypi.service';
import { PypiPackagesVersionDetailComponent } from './pypi-packages-version-detail.component';

@Component({ selector: 'app-security-scan-section', standalone: true, template: '' })
class SecurityScanSectionStubComponent {
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Input() public artifactName: string;
  @Input() public artifactVersion: string;
  @Input() public canTriggerScan: boolean;
}

describe('PypiPackagesVersionDetailComponent description', () => {
  let pypiService: jasmine.SpyObj<PypiService>;

  function render(
    description: string | undefined,
    descriptionContentType: string | undefined,
    extra: Partial<ReleaseDetail> = {},
  ): HTMLElement {
    const versionInfo: ReleaseDetail = {
      packageName: 'acme-lib',
      version: '1.2.3',
      classifiers: [],
      description,
      descriptionContentType,
      ...extra,
    };
    pypiService.fetchRelease.and.returnValue(of(versionInfo));

    const fixture: ComponentFixture<PypiPackagesVersionDetailComponent> = TestBed.createComponent(
      PypiPackagesVersionDetailComponent,
    );
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    const repoChanges = new BehaviorSubject<RepoPermissionInfo>({
      repoName: 'pypi-repo',
      canRead: true,
      canWrite: true,
      canManage: true,
      private: false,
    });
    pypiService = jasmine.createSpyObj<PypiService>('PypiService', ['fetchRelease', 'deleteRelease'], {
      repoChanges,
    });

    TestBed.configureTestingModule({
      imports: [PypiPackagesVersionDetailComponent],
      providers: [
        { provide: PypiService, useValue: pypiService },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ package: 'acme-lib', version: '1.2.3' }) } },
        },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigateByUrl']) },
        {
          provide: BreadcrumbSecurityLinkService,
          useValue: jasmine.createSpyObj<BreadcrumbSecurityLinkService>('BreadcrumbSecurityLinkService', [
            'show',
            'clear',
          ]),
        },
        {
          provide: RepoLookupService,
          useValue: { currentRepo: { repoName: 'pypi-repo', repoType: 'pypi' } },
        },
      ],
    });
    TestBed.overrideComponent(PypiPackagesVersionDetailComponent, {
      remove: { imports: [SecurityScanSectionComponent] },
      add: { imports: [SecurityScanSectionStubComponent] },
    });
  });

  it('renders a text/markdown description through app-markdown', () => {
    const el = render('# Acme readme\n\nUse **acme-lib** like this.', 'text/markdown');

    const description = el.querySelector('[data-testid="readme"]');
    expect(description).not.toBeNull();
    expect(description?.querySelector('h1')?.textContent).toBe('Acme readme');
    expect(description?.querySelector('strong')?.textContent).toBe('acme-lib');
    expect(description?.querySelector('pre')).toBeNull();
  });

  it('renders a text/x-rst description as plain preformatted text', () => {
    const el = render('Acme\n====\n\nUse **acme-lib** like this.', 'text/x-rst');

    const description = el.querySelector('[data-testid="readme"]');
    expect(description).not.toBeNull();
    const pre = description?.querySelector('pre');
    expect(pre).not.toBeNull();
    expect(pre?.textContent).toBe('Acme\n====\n\nUse **acme-lib** like this.');
    expect(description?.querySelector('h1')).toBeNull();
  });

  it('renders a text/plain description as plain preformatted text', () => {
    const el = render('Just plain text.', 'text/plain');

    const description = el.querySelector('[data-testid="readme"]');
    expect(description?.querySelector('pre')?.textContent).toBe('Just plain text.');
  });

  it('falls back to plain preformatted text when the content type is missing (defaults to RST)', () => {
    const el = render('Some description with no declared content type.', undefined);

    const description = el.querySelector('[data-testid="readme"]');
    expect(description?.querySelector('pre')?.textContent).toBe('Some description with no declared content type.');
  });

  it('hides the section when the version has no description', () => {
    const el = render(undefined, undefined);

    expect(el.textContent).toContain('Installation');
    expect(el.querySelector('[data-testid="readme"]')).toBeNull();
    expect(el.textContent).not.toContain('Description');
  });

  it('hides the section for a blank description', () => {
    const el = render('  \n\n ', 'text/markdown');

    expect(el.querySelector('[data-testid="readme"]')).toBeNull();
  });

  it('shows an install command for the package and version being viewed, not a placeholder', () => {
    const el = render('desc', 'text/markdown');

    expect(el.textContent).toContain(
      `pip install acme-lib==1.2.3 --extra-index-url ${environment.repoBaseUrl}/pypi-repo/simple`,
    );
    expect(el.textContent).not.toContain('hello-world');
  });

  describe('the release kind label', () => {
    const kind = (extra: Partial<ReleaseDetail>): string | undefined =>
      render(undefined, undefined, extra).querySelector('[data-testid="pkg-detail-release-kind"]')?.textContent?.trim();

    it('names each kind of release, a post release included (RPS-1261)', () => {
      expect(kind({ finalRelease: true })).toBe('Final release:');
      expect(kind({ preRelease: true })).toBe('Pre release:');
      expect(kind({ postRelease: true })).toBe('Post release:');
      expect(kind({ devRelease: true })).toBe('Dev Release:');
    });
  });
});

describe('PypiPackagesVersionDetailComponent delete (RPS-1288)', () => {
  const REPO = 'pypi-repo';
  let pypiService: jasmine.SpyObj<PypiService>;
  let router: jasmine.SpyObj<Router>;
  let toast: jasmine.SpyObj<ToastService>;
  let danger: jasmine.SpyObj<DangerModalService>;
  const route = {
    snapshot: { paramMap: convertToParamMap({ package: 'acme-lib', version: '1.2.3' }) },
  } as ActivatedRoute;

  function render(): ComponentFixture<PypiPackagesVersionDetailComponent> {
    const fixture = TestBed.createComponent(PypiPackagesVersionDetailComponent);
    fixture.detectChanges();
    return fixture;
  }

  function confirmDelete(fixture: ComponentFixture<PypiPackagesVersionDetailComponent>): Promise<void> {
    fixture.componentInstance.deleteVersion();
    danger.show.calls.mostRecent().args[2]();
    return fixture.whenStable();
  }

  beforeEach(() => {
    pypiService = jasmine.createSpyObj<PypiService>(
      'PypiService',
      ['fetchRelease', 'fetchPackageReleasesLikeName', 'deleteRelease'],
      {
        repoChanges: new BehaviorSubject<RepoPermissionInfo>({
          repoName: REPO,
          canRead: true,
          canWrite: true,
          canManage: true,
          private: false,
        }),
      },
    );
    pypiService.fetchRelease.and.returnValue(
      of({ packageName: 'acme-lib', version: '1.2.3', classifiers: [] } as ReleaseDetail),
    );
    pypiService.fetchPackageReleasesLikeName.and.returnValue(of({ content: [{}, {}] } as never));
    pypiService.deleteRelease.and.returnValue(of(undefined));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    danger = jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']);

    TestBed.configureTestingModule({
      imports: [PypiPackagesVersionDetailComponent],
      providers: [
        { provide: PypiService, useValue: pypiService },
        { provide: ActivatedRoute, useValue: route },
        { provide: ToastService, useValue: toast },
        { provide: DangerModalService, useValue: danger },
        { provide: Router, useValue: router },
        {
          provide: BreadcrumbSecurityLinkService,
          useValue: jasmine.createSpyObj<BreadcrumbSecurityLinkService>('BreadcrumbSecurityLinkService', [
            'show',
            'clear',
          ]),
        },
        { provide: RepoLookupService, useValue: { currentRepo: { repoName: REPO, repoType: 'pypi' } } },
      ],
    });
    TestBed.overrideComponent(PypiPackagesVersionDetailComponent, {
      remove: { imports: [SecurityScanSectionComponent] },
      add: { imports: [SecurityScanSectionStubComponent] },
    });
  });

  it('goes to the versions page of the package after deleting one of several releases', async () => {
    const fixture = render();

    await confirmDelete(fixture);

    expect(pypiService.fetchPackageReleasesLikeName).toHaveBeenCalledOnceWith('acme-lib', '', VERSION_PROBE_SORT, 0, 2);
    expect(pypiService.deleteRelease).toHaveBeenCalledOnceWith('acme-lib', '1.2.3');
    expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
    expect(toast.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
  });

  it('goes to the package list of the repository after the last release', async () => {
    pypiService.fetchPackageReleasesLikeName.and.returnValue(of({ content: [{}] } as never));
    const fixture = render();

    await confirmDelete(fixture);

    expect(router.navigate).toHaveBeenCalledOnceWith(['/', REPO]);
    expect(toast.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
  });

  it('deletes nothing when the releases of the package cannot be read', async () => {
    pypiService.fetchPackageReleasesLikeName.and.returnValue(throwError(() => new Error('boom')));
    const fixture = render();

    await confirmDelete(fixture);

    expect(pypiService.deleteRelease).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
