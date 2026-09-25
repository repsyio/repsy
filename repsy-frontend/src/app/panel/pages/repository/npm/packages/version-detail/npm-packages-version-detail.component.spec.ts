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
import { PackageVersionDetail, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/service/breadcrumb-security-link.service';
import { VERSION_PROBE_SORT } from '../../../../../shared/util/version-delete-landing.util';
import { RepoLookupService } from '../../../repo-entry/repo-lookup.service';
import { NpmService } from '../../service/npm.service';
import { NpmPackagesVersionDetailComponent } from './npm-packages-version-detail.component';

@Component({ selector: 'app-security-scan-section', standalone: true, template: '' })
class SecurityScanSectionStubComponent {
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Input() public artifactName: string;
  @Input() public artifactVersion: string;
  @Input() public canTriggerScan: boolean;
}

describe('NpmPackagesVersionDetailComponent README', () => {
  let npmService: jasmine.SpyObj<NpmService>;

  function render(readme: string | undefined, extra: Partial<PackageVersionDetail> = {}): HTMLElement {
    const versionInfo: PackageVersionDetail = { packageName: 'acme-lib', readme, ...extra };
    npmService.fetchPackageVersion.and.returnValue(of(versionInfo));

    const fixture: ComponentFixture<NpmPackagesVersionDetailComponent> = TestBed.createComponent(
      NpmPackagesVersionDetailComponent,
    );
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    const repoChanges = new BehaviorSubject<RepoPermissionInfo>({
      repoName: 'npm-repo',
      canRead: true,
      canWrite: true,
      canManage: true,
      private: false,
    });
    npmService = jasmine.createSpyObj<NpmService>('NpmService', ['fetchPackageVersion'], { repoChanges });

    TestBed.configureTestingModule({
      imports: [NpmPackagesVersionDetailComponent],
      providers: [
        { provide: NpmService, useValue: npmService },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ scope: '~', package: 'acme-lib', version: '1.2.3' }) },
          },
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
          useValue: { currentRepo: { repoName: 'npm-repo', repoType: 'npm' } },
        },
      ],
    });
    TestBed.overrideComponent(NpmPackagesVersionDetailComponent, {
      remove: { imports: [SecurityScanSectionComponent] },
      add: { imports: [SecurityScanSectionStubComponent] },
    });
  });

  it('renders the README markdown', () => {
    const el = render('# Acme readme\n\nUse **acme-lib** like this.');

    const readme = el.querySelector('[data-testid="readme"]');
    expect(readme).not.toBeNull();
    expect(readme?.querySelector('h1')?.textContent).toBe('Acme readme');
    expect(readme?.querySelector('strong')?.textContent).toBe('acme-lib');
  });

  it('hides the section when the version has no README', () => {
    const el = render(undefined);

    expect(el.textContent).toContain('Installation');
    expect(el.querySelector('[data-testid="readme"]')).toBeNull();
    expect(el.textContent).not.toContain('README');
  });

  it('hides the section for a blank README', () => {
    const el = render('  \n\n ');

    expect(el.querySelector('[data-testid="readme"]')).toBeNull();
  });

  function metadataLine(el: HTMLElement, label: string): string {
    const line = Array.from(el.querySelectorAll('[data-testid="pkg-detail-metadata"] > div')).find((div) =>
      div.textContent?.includes(label),
    );
    return line?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  it('prints the bugs URL of the package, not its name', () => {
    const el = render(undefined, { bugsUrl: 'https://example.com/bugs' });

    expect(metadataLine(el, 'Bugs URL:')).toBe('Bugs URL: https://example.com/bugs');
  });

  it('says so when the package has no bugs URL', () => {
    const el = render(undefined);

    expect(metadataLine(el, 'Bugs URL:')).toBe('Bugs URL: No bugs URL found!');
  });

  it('lists the keywords of the package', () => {
    const el = render(undefined, { keywords: [{ keyword: 'alpha' }, { keyword: 'beta' }] });

    expect(metadataLine(el, 'Keywords:')).toBe('Keywords: alpha, beta');
  });

  it('says so when the package has no keywords', () => {
    expect(metadataLine(render(undefined), 'Keywords:')).toBe('Keywords: No keywords found!');
    expect(metadataLine(render(undefined, { keywords: [] }), 'Keywords:')).toBe('Keywords: No keywords found!');
  });
});

describe('NpmPackagesVersionDetailComponent registry snippet and delete (RPS-1288)', () => {
  const REPO = 'npm-repo';
  let npmService: jasmine.SpyObj<NpmService>;
  let router: jasmine.SpyObj<Router>;
  let toast: jasmine.SpyObj<ToastService>;
  let danger: jasmine.SpyObj<DangerModalService>;
  let route: ActivatedRoute;

  function render(scope: string): ComponentFixture<NpmPackagesVersionDetailComponent> {
    route = {
      snapshot: { paramMap: convertToParamMap({ scope, package: 'acme-lib', version: '1.2.3' }) },
    } as ActivatedRoute;
    TestBed.overrideProvider(ActivatedRoute, { useValue: route });
    const fixture = TestBed.createComponent(NpmPackagesVersionDetailComponent);
    fixture.detectChanges();
    return fixture;
  }

  const text = (fixture: ComponentFixture<NpmPackagesVersionDetailComponent>, testId: string): string =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`)?.textContent?.trim() ?? '';

  beforeEach(() => {
    npmService = jasmine.createSpyObj<NpmService>(
      'NpmService',
      ['fetchPackageVersion', 'searchPackageVersions', 'deletePackageVersion'],
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
    npmService.fetchPackageVersion.and.returnValue(of({ packageName: 'acme-lib' } as PackageVersionDetail));
    npmService.searchPackageVersions.and.returnValue(of({ content: [{}, {}] } as never));
    npmService.deletePackageVersion.and.returnValue(of(undefined));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    danger = jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']);

    TestBed.configureTestingModule({
      imports: [NpmPackagesVersionDetailComponent],
      providers: [
        { provide: NpmService, useValue: npmService },
        { provide: ActivatedRoute, useValue: {} },
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
        { provide: RepoLookupService, useValue: { currentRepo: { repoName: REPO, repoType: 'npm' } } },
      ],
    });
    TestBed.overrideComponent(NpmPackagesVersionDetailComponent, {
      remove: { imports: [SecurityScanSectionComponent] },
      add: { imports: [SecurityScanSectionStubComponent] },
    });
  });

  it('shows the registry line of this repository for an unscoped package, next to the install command', () => {
    const fixture = render('~');

    expect(text(fixture, 'pkg-detail-snippet-npmrc-text')).toBe(`registry=${environment.repoBaseUrl}/${REPO}/`);
    expect(text(fixture, 'pkg-detail-install-text')).toBe('npm install acme-lib');
  });

  it('scopes the registry line to the scope of a scoped package', () => {
    const fixture = render('acme');

    expect(text(fixture, 'pkg-detail-snippet-npmrc-text')).toBe(`@acme:registry=${environment.repoBaseUrl}/${REPO}/`);
    expect(text(fixture, 'pkg-detail-install-text')).toBe('npm install @acme/acme-lib');
  });

  function confirmDelete(fixture: ComponentFixture<NpmPackagesVersionDetailComponent>): Promise<void> {
    fixture.componentInstance.deleteVersion();
    danger.show.calls.mostRecent().args[2]();
    return fixture.whenStable();
  }

  it('goes to the versions page of the package after deleting one of several versions', async () => {
    const fixture = render('acme');

    await confirmDelete(fixture);

    expect(npmService.searchPackageVersions).toHaveBeenCalledOnceWith('acme-lib', 'acme', '', VERSION_PROBE_SORT, 0, 2);
    expect(npmService.deletePackageVersion).toHaveBeenCalledOnceWith('acme-lib', 'acme', '1.2.3');
    expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
    expect(toast.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
  });

  it('goes to the package list of the repository after the last version', async () => {
    npmService.searchPackageVersions.and.returnValue(of({ content: [{}] } as never));
    const fixture = render('~');

    await confirmDelete(fixture);

    expect(router.navigate).toHaveBeenCalledOnceWith(['/', REPO]);
    expect(toast.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
  });

  it('deletes nothing when the versions of the package cannot be read', async () => {
    npmService.searchPackageVersions.and.returnValue(throwError(() => new Error('boom')));
    const fixture = render('~');

    await confirmDelete(fixture);

    expect(npmService.deletePackageVersion).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
