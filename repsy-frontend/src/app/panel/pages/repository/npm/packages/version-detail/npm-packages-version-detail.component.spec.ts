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
import { BehaviorSubject, of } from 'rxjs';

import { PackageVersionDetail, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/service/breadcrumb-security-link.service';
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
