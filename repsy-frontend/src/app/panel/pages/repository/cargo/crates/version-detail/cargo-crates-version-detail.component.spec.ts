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

import { Component, Directive, Input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { HighlightLineNumbers } from 'ngx-highlightjs/line-numbers';
import { BehaviorSubject, of } from 'rxjs';

import { RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { CrateInfo } from '../../dto/crate-info';
import { CrateVersionInfo } from '../../dto/crate-version-info';
import { CargoService } from '../../service/cargo.service';
import { CargoCratesVersionDetailComponent } from './cargo-crates-version-detail.component';

@Component({ selector: 'app-security-scan-section', standalone: true, template: '' })
class SecurityScanSectionStubComponent {
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Input() public artifactName: string;
  @Input() public artifactVersion: string;
  @Input() public canTriggerScan: boolean;
}

// highlight.js is loaded lazily and is irrelevant to the README, so the highlighting directives are stubbed.
// eslint-disable-next-line @angular-eslint/directive-selector -- must match the selector of the real directive
@Directive({ selector: '[highlight]', standalone: true })
class HighlightStubDirective {
  @Input() public highlight: string;
  @Input() public language: string;
}

// eslint-disable-next-line @angular-eslint/directive-selector -- must match the selector of the real directive
@Directive({ selector: '[lineNumbers]', standalone: true })
class HighlightLineNumbersStubDirective {}

describe('CargoCratesVersionDetailComponent README', () => {
  let cargoService: jasmine.SpyObj<CargoService>;

  function render(readme: string | undefined): HTMLElement {
    const crateVersion = Object.assign(new CrateVersionInfo(), {
      name: 'acme-lib',
      version: '1.2.3',
      hasLib: true,
      deps: [],
      downloads: 0,
      created_at: new Date('2026-01-01T00:00:00Z'),
      readme,
    });
    cargoService.fetchCrate.and.returnValue(of(Object.assign(new CrateInfo(), { original_name: 'acme-lib' })));
    cargoService.fetchCrateVersion.and.returnValue(of(crateVersion));

    const fixture: ComponentFixture<CargoCratesVersionDetailComponent> = TestBed.createComponent(
      CargoCratesVersionDetailComponent,
    );
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    const repoChanges = new BehaviorSubject<RepoPermissionInfo>({
      repoName: 'cargo-repo',
      canRead: true,
      canWrite: true,
      canManage: true,
      private: false,
    });
    cargoService = jasmine.createSpyObj<CargoService>('CargoService', ['fetchCrate', 'fetchCrateVersion'], {
      repoChanges,
    });

    TestBed.configureTestingModule({
      imports: [CargoCratesVersionDetailComponent],
      providers: [
        { provide: CargoService, useValue: cargoService },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ crate: 'acme-lib', version: '1.2.3' }) } },
        },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigateByUrl']) },
      ],
    });
    TestBed.overrideComponent(CargoCratesVersionDetailComponent, {
      remove: { imports: [SecurityScanSectionComponent, Highlight, HighlightLineNumbers] },
      add: { imports: [SecurityScanSectionStubComponent, HighlightStubDirective, HighlightLineNumbersStubDirective] },
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

    expect(el.querySelector('[data-testid="readme"]')).toBeNull();
    expect(el.textContent).not.toContain('README');
  });

  it('hides the section for a blank README', () => {
    const el = render('  \n\n ');

    expect(el.querySelector('[data-testid="readme"]')).toBeNull();
  });
});
