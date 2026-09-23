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
import {
  describeEmptyingDelete,
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
