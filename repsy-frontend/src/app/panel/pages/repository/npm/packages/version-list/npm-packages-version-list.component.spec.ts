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

import { fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import moment from 'moment';
import { BehaviorSubject, of, throwError } from 'rxjs';

import { RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { SecurityService } from '../../../../security/service/security.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { renderComponent } from '../../../testing/render-spec-helpers';
import {
  describeEmptyingDelete,
  describePagedDelete,
  describeRepoListBehavior,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { NpmService } from '../../service/npm.service';
import { NpmPackagesVersionListComponent } from './npm-packages-version-list.component';

describe('NpmPackagesVersionListComponent', () => {
  let component: NpmPackagesVersionListComponent;
  let npmService: jasmine.SpyObj<NpmService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;
  const VERSION = { version: '1.0.0' } as Parameters<NpmPackagesVersionListComponent['deleteVersion']>[0];

  function build(scope = 'acme'): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    npmService = jasmine.createSpyObj<NpmService>(
      'NpmService',
      ['searchPackageVersions', 'fetchPackageTags', 'deletePackageVersion'],
      { repoChanges },
    );
    npmService.fetchPackageTags.and.returnValue(of([]));
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    dangerModalService = new DangerModalService();
    component = new NpmPackagesVersionListComponent(
      { snapshot: { paramMap: convertToParamMap({ scope, package: 'ui' }) } } as ActivatedRoute,
      npmService,
      toastService,
      dangerModalService,
      router,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: npmService.searchPackageVersions,
      args: { search: 2, sort: 3, page: 4 },
      respond: (content, totalPages) =>
        npmService.searchPackageVersions.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => npmService.searchPackageVersions.and.returnValue(throwError(() => 'boom')),
      security: {
        watch: securityService.watchVersionSecuritySummary,
        argsFor: (repoName) => [repoName, '@acme/ui'],
      },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, { security: true, search: { typed: '1.0', loaded: '1.0' } }));

  describe('search', () => {
    it('drops a leading v from the version the user typed', () => {
      build();
      npmService.searchPackageVersions.and.returnValue(of(pageOf([], 0) as never));

      component.search('v1.0.0');

      expect(component.searchText).toBe('1.0.0');
      expect(npmService.searchPackageVersions.calls.mostRecent().args[2]).toBe('1.0.0');
    });
  });

  describe('the listing', () => {
    it('loads the versions of the scoped package in the route, then its distribution tags', fakeAsync(() => {
      const tags = [{ tag: 'latest', version: '1.0.0' }] as never;
      build().respond([VERSION], 1);
      npmService.fetchPackageTags.and.returnValue(of(tags));

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(npmService.searchPackageVersions).toHaveBeenCalledOnceWith('ui', 'acme', '', component.sortOption, 0, 10);
      expect(npmService.fetchPackageTags).toHaveBeenCalledOnceWith('ui', 'acme');
      expect(component.tags).toBe(tags);
      expect(component.securityArtifactName).toBe('@acme/ui');
    }));

    it('treats the ~ scope as no scope at all', fakeAsync(() => {
      build('~').respond([VERSION], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.scopeName).toBeNull();
      expect(npmService.searchPackageVersions).toHaveBeenCalledOnceWith('ui', null, '', component.sortOption, 0, 10);
      expect(component.securityArtifactName).toBe('ui');
    }));
  });

  describe('deleteVersion', () => {
    describeEmptyingDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: npmService.deletePackageVersion,
      invoke: () => component.deleteVersion(VERSION),
      title: 'Delete Version',
      message: 'Version deleted successfully',
      removeArgs: ['ui', 'acme', '1.0.0'],
      navigate: router.navigateByUrl,
      navigateArgs: [`/${REPO_NAME}`],
    }));
  });

  describe('deleting from a later page or under a search (RPS-1340)', () => {
    describePagedDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      remove: npmService.deletePackageVersion,
      invoke: () => component.deleteVersion(VERSION),
      navigate: router.navigateByUrl,
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

describe('NpmPackagesVersionListComponent template', () => {
  async function render(flags: Parameters<typeof permission>[1]): Promise<HTMLElement> {
    const npmService = jasmine.createSpyObj<NpmService>('NpmService', ['searchPackageVersions', 'fetchPackageTags'], {
      repoChanges: new BehaviorSubject<RepoPermissionInfo | null>(permission(REPO_NAME, flags)),
    });
    npmService.fetchPackageTags.and.returnValue(of([]));
    npmService.searchPackageVersions.and.returnValue(
      of(pageOf([{ version: '1.0.0', createdAt: '2026-01-01T00:00:00Z' }], 1) as never),
    );
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));

    const { el } = await renderComponent(NpmPackagesVersionListComponent, [
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: convertToParamMap({ scope: '~', package: 'ui' }) } },
      },
      { provide: NpmService, useValue: npmService },
      { provide: SecurityService, useValue: securityService },
    ]);
    return el;
  }

  const cardMenu = '[data-testid="pkg-versions-card-1.0.0"] [data-testid="row-menu"]';

  it('offers Delete on the mobile card to a manager', async () => {
    expect((await render({ canWrite: true, canManage: true })).querySelector(cardMenu)).not.toBeNull();
  });

  it('offers no Delete on the mobile card to a user who can write but not manage (RPS-1262)', async () => {
    expect((await render({ canWrite: true, canManage: false })).querySelector(cardMenu)).toBeNull();
  });

  it('labels the upload time "Uploaded:" on the mobile card (RPS-1261)', async () => {
    const el = await render({ canManage: true });

    const card = el.querySelector('[data-testid="pkg-versions-card-1.0.0"]');
    expect(card?.textContent).toContain('Uploaded:');
    expect(card?.textContent).not.toContain('Upladed');
  });
});
