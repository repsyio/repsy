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

import { HttpErrorResponse } from '@angular/common/http';
import { Component, Input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { NuGetVersionInfo, NuGetVersionListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { PagedData } from '../../../../../shared/dto/paged-data';
import { NugetService } from '../../service/nuget.service';
import { NugetPackagesVersionDetailComponent } from './nuget-packages-version-detail.component';

@Component({ selector: 'app-security-scan-section', standalone: true, template: '' })
class SecurityScanSectionStubComponent {
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Input() public artifactName: string;
  @Input() public artifactVersion: string;
  @Input() public canTriggerScan: boolean;
}

describe('NugetPackagesVersionDetailComponent README', () => {
  let nugetService: jasmine.SpyObj<NugetService>;

  async function render(readme: string | undefined): Promise<HTMLElement> {
    const versionInfo: NuGetVersionInfo = {
      packageId: 'Acme.Lib',
      version: '1.2.3',
      listed: true,
      downloadCount: 0,
      publishedAt: '2026-01-01T00:00:00Z',
      readme,
    };
    nugetService.fetchPackageVersion.and.resolveTo(versionInfo);

    const fixture: ComponentFixture<NugetPackagesVersionDetailComponent> = TestBed.createComponent(
      NugetPackagesVersionDetailComponent,
    );
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    const repoChanges = new BehaviorSubject<RepoPermissionInfo>({
      repoName: 'nuget-repo',
      canRead: true,
      canWrite: false,
      canManage: false,
      private: false,
    });
    nugetService = jasmine.createSpyObj<NugetService>('NugetService', ['fetchPackageVersion'], {
      repoChanges,
    });

    TestBed.configureTestingModule({
      imports: [NugetPackagesVersionDetailComponent],
      providers: [
        { provide: NugetService, useValue: nugetService },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ packageName: 'Acme.Lib', version: '1.2.3' }) } },
        },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
        {
          provide: DangerModalService,
          useValue: jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']),
        },
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
      ],
    });
    TestBed.overrideComponent(NugetPackagesVersionDetailComponent, {
      remove: { imports: [SecurityScanSectionComponent] },
      add: { imports: [SecurityScanSectionStubComponent] },
    });
  });

  async function renderFailure(status: number): Promise<HTMLElement> {
    nugetService.fetchPackageVersion.and.rejectWith(new HttpErrorResponse({ status: status }));
    const fixture = TestBed.createComponent(NugetPackagesVersionDetailComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('drops --source from the .NET CLI install command (RPS-1570)', async () => {
    // `dotnet add package --source` only accepts a URL or a folder, never a configured source's
    // name, so `--source repsy` is not a valid invocation. NuGet.Config already provides the
    // source once configured (Option A), so the CLI command needs no --source flag at all; the
    // URL form (installCommandUrl) is the one that legitimately passes the full URL.
    nugetService.fetchPackageVersion.and.resolveTo({
      packageId: 'Acme.Lib',
      version: '1.2.3',
      listed: true,
      downloadCount: 0,
      publishedAt: '2026-01-01T00:00:00Z',
    } as NuGetVersionInfo);
    const fixture: ComponentFixture<NugetPackagesVersionDetailComponent> = TestBed.createComponent(
      NugetPackagesVersionDetailComponent,
    );
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.installCommand).toBe('dotnet add package Acme.Lib --version 1.2.3');
    expect(fixture.componentInstance.installCommand).not.toContain('--source');
  });

  it('shows the not-found state, not an empty detail, when the version does not exist', async () => {
    const el = await renderFailure(404);

    expect(el.querySelector('[data-testid="pkg-detail"]')).toBeNull();
    expect(el.querySelector('[data-testid="pkg-error-message"]')?.textContent?.trim()).toBe(
      "Version '1.2.3' not found",
    );
  });

  it('says the version could not be loaded when the request fails otherwise', async () => {
    const el = await renderFailure(500);

    expect(el.querySelector('[data-testid="pkg-detail"]')).toBeNull();
    expect(el.querySelector('[data-testid="pkg-error-message"]')?.textContent?.trim()).toBe(
      'The version could not be loaded',
    );
  });

  it('renders the README markdown', async () => {
    const el = await render('# Acme readme\n\nUse **Acme.Lib** like this.');

    const readme = el.querySelector('[data-testid="readme"]');
    expect(readme).not.toBeNull();
    expect(readme?.querySelector('h1')?.textContent).toBe('Acme readme');
    expect(readme?.querySelector('strong')?.textContent).toBe('Acme.Lib');
  });

  it('does not run scripts embedded in the README', async () => {
    const el = await render(
      'Hi\n\n<script>window.__pwned = true</script>\n\n<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" onerror="window.__pwned = true">',
    );

    expect(el.querySelector('[data-testid="readme"] script')).toBeNull();
    expect(el.querySelector('[data-testid="readme"]')?.innerHTML).not.toContain('onerror');
    expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined();
  });

  it('hides the section when the version has no README', async () => {
    const el = await render(undefined);

    expect(el.querySelector('[data-testid="readme"]')).toBeNull();
    expect(el.textContent).not.toContain('README');
  });

  it('hides the section for a blank README', async () => {
    const el = await render('  \n\n ');

    expect(el.querySelector('[data-testid="readme"]')).toBeNull();
  });

  it('shows a not-found message on the page, without a second toast, when the version cannot be loaded', async () => {
    nugetService.fetchPackageVersion.and.rejectWith(
      new HttpErrorResponse({ status: 404, error: { detail: 'Version not found' } }),
    );
    const fixture = TestBed.createComponent(NugetPackagesVersionDetailComponent);

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBe("Version '1.2.3' not found");
    expect(fixture.componentInstance.loading).toBeFalse();
    expect(TestBed.inject(ToastService).show).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain("Version '1.2.3' not found");
  });
});

describe('NugetPackagesVersionDetailComponent delete (RPS-1288)', () => {
  const REPO = 'nuget-repo';
  let nugetService: jasmine.SpyObj<NugetService>;
  let router: jasmine.SpyObj<Router>;
  let toast: jasmine.SpyObj<ToastService>;
  let danger: jasmine.SpyObj<DangerModalService>;
  const route = {
    snapshot: { paramMap: convertToParamMap({ packageName: 'Acme.Lib', version: '1.2.3' }) },
  } as ActivatedRoute;

  async function confirmDelete(): Promise<void> {
    const fixture = TestBed.createComponent(NugetPackagesVersionDetailComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.componentInstance.deleteVersion();
    danger.show.calls.mostRecent().args[2]();
    // the probe, the delete and the landing are three promises in a row
    await new Promise<void>((resolve) => setTimeout(resolve));
    await fixture.whenStable();
  }

  function probeOf(count: number): PagedData<NuGetVersionListItem> {
    return {
      content: Array.from({ length: count }, () => ({}) as NuGetVersionListItem),
      page: undefined,
    } as unknown as PagedData<NuGetVersionListItem>;
  }

  beforeEach(() => {
    nugetService = jasmine.createSpyObj<NugetService>(
      'NugetService',
      ['fetchPackageVersion', 'fetchPackageVersions', 'deletePackageVersion'],
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
    nugetService.fetchPackageVersion.and.resolveTo({ packageId: 'Acme.Lib', version: '1.2.3' } as NuGetVersionInfo);
    nugetService.fetchPackageVersions.and.resolveTo(probeOf(2));
    nugetService.deletePackageVersion.and.resolveTo();
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    danger = jasmine.createSpyObj<DangerModalService>('DangerModalService', ['show']);

    TestBed.configureTestingModule({
      imports: [NugetPackagesVersionDetailComponent],
      providers: [
        { provide: NugetService, useValue: nugetService },
        { provide: ActivatedRoute, useValue: route },
        { provide: ToastService, useValue: toast },
        { provide: DangerModalService, useValue: danger },
        { provide: Router, useValue: router },
      ],
    });
    TestBed.overrideComponent(NugetPackagesVersionDetailComponent, {
      remove: { imports: [SecurityScanSectionComponent] },
      add: { imports: [SecurityScanSectionStubComponent] },
    });
  });

  it('goes to the versions page of the package when only the version was deleted', async () => {
    nugetService.fetchPackageVersions.and.resolveTo(probeOf(2));

    await confirmDelete();

    expect(nugetService.fetchPackageVersions).toHaveBeenCalledOnceWith('Acme.Lib', '', jasmine.anything(), 0, 2);
    expect(nugetService.deletePackageVersion).toHaveBeenCalledOnceWith('Acme.Lib', '1.2.3');
    expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
    expect(toast.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
  });

  it('goes to the package list of the repository when the package went with its last version', async () => {
    nugetService.fetchPackageVersions.and.resolveTo(probeOf(1));

    await confirmDelete();

    expect(router.navigate).toHaveBeenCalledOnceWith(['/', REPO]);
    expect(toast.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
  });

  it('stays on the page, without a toast, when the delete fails', async () => {
    nugetService.deletePackageVersion.and.rejectWith(new HttpErrorResponse({ status: 500 }));

    await confirmDelete();

    expect(router.navigate).not.toHaveBeenCalled();
    expect(toast.show).not.toHaveBeenCalled();
  });
});
