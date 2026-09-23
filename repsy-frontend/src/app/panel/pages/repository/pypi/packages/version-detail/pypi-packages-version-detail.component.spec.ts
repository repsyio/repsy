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

import { ReleaseDetail, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/service/breadcrumb-security-link.service';
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

  function render(description: string | undefined, descriptionContentType: string | undefined): HTMLElement {
    const versionInfo: ReleaseDetail = {
      packageName: 'acme-lib',
      version: '1.2.3',
      classifiers: [],
      description,
      descriptionContentType,
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
});
