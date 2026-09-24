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

import { HttpErrorResponse } from '@angular/common/http';
import { fakeAsync, flushMicrotasks, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import moment from 'moment';
import { BehaviorSubject, of } from 'rxjs';

import {
  NuGetDeletedItem,
  NuGetPackageInfo,
  NuGetVersionListItem,
  RepoPermissionInfo,
} from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { SecurityService } from '../../../../security/service/security.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { renderComponent, testIds } from '../../../testing/render-spec-helpers';
import {
  describeLastVersionDelete,
  describeRepoListBehavior,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { NugetService } from '../../service/nuget.service';
import { NugetPackagesVersionListComponent } from './nuget-packages-version-list.component';

const PACKAGE = { packageId: 'Acme.Lib' } as NuGetPackageInfo;
const VERSION = { version: '1.0.0' } as NuGetVersionListItem;

describe('NugetPackagesVersionListComponent', () => {
  let component: NugetPackagesVersionListComponent;
  let route: ActivatedRoute;
  let nugetService: jasmine.SpyObj<NugetService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    nugetService = jasmine.createSpyObj<NugetService>(
      'NugetService',
      ['fetchPackage', 'fetchPackageVersions', 'deletePackage', 'deletePackageVersion'],
      { repoChanges },
    );
    nugetService.fetchPackage.and.resolveTo(PACKAGE);
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    dangerModalService = new DangerModalService();
    route = { snapshot: { paramMap: convertToParamMap({ packageId: 'Acme.Lib' }) } } as ActivatedRoute;
    component = new NugetPackagesVersionListComponent(
      route,
      { username: 'alice' } as AuthService,
      nugetService,
      toastService,
      dangerModalService,
      router,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: nugetService.fetchPackageVersions,
      args: { sort: 1, page: 2 },
      respond: (content, totalPages) =>
        nugetService.fetchPackageVersions.and.resolveTo(pageOf(content, totalPages) as never),
      fail: () =>
        nugetService.fetchPackageVersions.and.rejectWith(
          new HttpErrorResponse({ status: 404, error: { text: 'Package not found' } }),
        ),
      security: { watch: securityService.watchVersionSecuritySummary, argsFor: (repoName) => [repoName, 'Acme.Lib'] },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, {
      security: true,
      sortResetsPage: true,
      failureMessage: 'Package not found',
    }));

  describe('the listing', () => {
    it('loads the package of the route, then its versions', fakeAsync(() => {
      build().respond([VERSION], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.packageId).toBe('Acme.Lib');
      expect(nugetService.fetchPackage).toHaveBeenCalledOnceWith('Acme.Lib');
      expect(nugetService.fetchPackageVersions).toHaveBeenCalledOnceWith('Acme.Lib', component.sortOption, 0, 10);
      expect(component.pkg).toBe(PACKAGE);
      expect(component.versions).toEqual([VERSION]);
      expect(component.error).toBeNull();
    }));

    it('does not load versions and shows a generic error when the package call fails without a message', fakeAsync(() => {
      build();
      nugetService.fetchPackage.and.rejectWith(new HttpErrorResponse({ status: 500 }));

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(nugetService.fetchPackageVersions).not.toHaveBeenCalled();
      expect(component.error).toBe('Error Occurred');
      expect(component.loading).toBeFalse();
    }));
  });

  describe('deleteVersion', () => {
    describeLastVersionDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      invoke: () => component.deleteVersion(VERSION),
      title: 'Delete Version',
      message: 'Version deleted successfully',
      removeVersion: nugetService.deletePackageVersion,
      removeVersionArgs: ['Acme.Lib', '1.0.0'],
      removePackage: nugetService.deletePackage,
      removePackageArgs: ['Acme.Lib'],
      navigate: router.navigate,
      navigateArgs: [['..'], { relativeTo: route }],
      answer: {
        ok: () => Promise.resolve(NuGetDeletedItem.Version),
        fail: () => Promise.reject(new HttpErrorResponse({ status: 409 })),
      },
    }));

    it('also leaves the listing when the server reports that it deleted the whole package', fakeAsync(() => {
      build().respond([{}, {}], 1);
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();
      nugetService.fetchPackageVersions.calls.reset();
      nugetService.deletePackageVersion.and.resolveTo(NuGetDeletedItem.Package);
      router.navigate.and.resolveTo(true);

      component.deleteVersion(VERSION);
      dangerModalService.call();
      flushMicrotasks();

      expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
      expect(nugetService.fetchPackageVersions).not.toHaveBeenCalled();
    }));
  });

  describe('helpers', () => {
    beforeEach(() => build());

    it('timeAgo renders a relative time', () => {
      expect(component.timeAgo(moment().subtract(3, 'days').toDate())).toBe('3 days ago');
    });

    it('openConfig toggles the config panel', () => {
      component.openConfig(true);
      expect(component.showConfig).toBeTrue();
      component.openConfig(false);
      expect(component.showConfig).toBeFalse();
    });
  });
});

describe('NugetPackagesVersionListComponent template', () => {
  async function render(canManage: boolean): Promise<HTMLElement> {
    const nugetService = jasmine.createSpyObj<NugetService>('NugetService', ['fetchPackage', 'fetchPackageVersions'], {
      repoChanges: new BehaviorSubject<RepoPermissionInfo | null>(permission(REPO_NAME, { canManage })),
    });
    nugetService.fetchPackage.and.resolveTo(PACKAGE);
    nugetService.fetchPackageVersions.and.resolveTo(
      pageOf(
        [
          { version: '1.0.0', publishedAt: '2026-01-01T00:00:00Z' },
          { version: '2.0.0', publishedAt: '2026-02-01T00:00:00Z' },
        ],
        1,
      ) as never,
    );
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));

    const { el } = await renderComponent(NugetPackagesVersionListComponent, [
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ packageId: 'Acme.Lib' }) } } },
      { provide: AuthService, useValue: { username: 'alice' } },
      { provide: NugetService, useValue: nugetService },
      { provide: SecurityService, useValue: securityService },
    ]);
    return el;
  }

  it('renders a mobile card per version next to the desktop row (RPS-1262)', async () => {
    const el = await render(true);

    expect(testIds(el, 'pkg-versions-card-')).toEqual(['pkg-versions-card-1.0.0', 'pkg-versions-card-2.0.0']);
    expect(testIds(el, 'pkg-versions-row-')).toEqual(['pkg-versions-row-1.0.0', 'pkg-versions-row-2.0.0']);
    const card = el.querySelector('[data-testid="pkg-versions-card-1.0.0"]');
    expect(card?.querySelector('[data-testid="row-name"]')?.textContent).toBe('1.0.0');
    expect(card?.querySelector('[data-testid="row-security"]')).not.toBeNull();
    expect(card?.querySelector('[data-testid="row-published"]')).not.toBeNull();
    expect(card?.closest('[data-testid="pkg-versions-cards"]')).not.toBeNull();
    expect(card?.parentElement?.classList).toContain('lg:hidden');
  });

  it('offers the row menu on a card to a manager only', async () => {
    const menus = '[data-testid^="pkg-versions-card-"] [data-testid="row-menu"]';
    expect((await render(true)).querySelectorAll(menus)).toHaveSize(2);

    TestBed.resetTestingModule();
    expect((await render(false)).querySelectorAll(menus)).toHaveSize(0);
  });
});
