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
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { SecurityService } from '../../../../security/service/security.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import {
  describeEmptyingDelete,
  describeRepoListBehavior,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { PypiService } from '../../service/pypi.service';
import { PypiPackagesVersionListComponent } from './pypi-packages-version-list.component';

describe('PypiPackagesVersionListComponent', () => {
  let component: PypiPackagesVersionListComponent;
  let pypiService: jasmine.SpyObj<PypiService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;
  const VERSION = { version: '2.31.0', finalRelease: false } as Parameters<
    PypiPackagesVersionListComponent['deleteVersion']
  >[0];

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    pypiService = jasmine.createSpyObj<PypiService>('PypiService', ['fetchPackageReleasesLikeName', 'deleteRelease'], {
      repoChanges,
    });
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    dangerModalService = new DangerModalService();
    component = new PypiPackagesVersionListComponent(
      { snapshot: { paramMap: convertToParamMap({ package: 'requests' }) } } as ActivatedRoute,
      { username: 'alice' } as AuthService,
      pypiService,
      toastService,
      dangerModalService,
      router,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: pypiService.fetchPackageReleasesLikeName,
      args: { search: 1, sort: 2, page: 3 },
      respond: (content, totalPages) =>
        pypiService.fetchPackageReleasesLikeName.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => pypiService.fetchPackageReleasesLikeName.and.returnValue(throwError(() => 'boom')),
      security: {
        watch: securityService.watchVersionSecuritySummary,
        argsFor: (repoName) => [repoName, 'requests'],
      },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, { security: true, search: { typed: '2.31', loaded: '2.31' } }));

  describe('the listing', () => {
    it('loads the releases of the package in the route', fakeAsync(() => {
      build().respond([VERSION], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(pypiService.fetchPackageReleasesLikeName).toHaveBeenCalledOnceWith(
        'requests',
        '',
        component.sortOption,
        0,
        10,
      );
    }));

    it('remembers the final release of the page, if there is one', fakeAsync(() => {
      build().respond(
        [
          { version: '3.0.0rc1', finalRelease: false },
          { version: '2.31.0', finalRelease: true },
        ],
        1,
      );

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.finalRelease).toBe('2.31.0');
    }));

    it('has no final release when the page has none', fakeAsync(() => {
      build().respond([{ version: '3.0.0rc1', finalRelease: false }], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.finalRelease).toBeUndefined();
    }));
  });

  describe('deleteVersion', () => {
    describeEmptyingDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: pypiService.deleteRelease,
      invoke: () => component.deleteVersion(VERSION),
      title: 'Delete Release',
      message: 'Version deleted successfully',
      removeArgs: ['requests', '2.31.0'],
      navigate: router.navigateByUrl,
      navigateArgs: [`/${REPO_NAME}`],
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
