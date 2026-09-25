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

import { GemVersionListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { SecurityService } from '../../../../security/service/security.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import {
  describeLastVersionDelete,
  describePagedDelete,
  describeRepoListBehavior,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { RubyService } from '../../service/ruby.service';
import { RubyGemsVersionListComponent } from './ruby-gems-version-list.component';

const VERSION = { version: '7.1.0', platform: 'ruby' } as GemVersionListItem;

describe('RubyGemsVersionListComponent', () => {
  let component: RubyGemsVersionListComponent;
  let route: ActivatedRoute;
  let rubyService: jasmine.SpyObj<RubyService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    rubyService = jasmine.createSpyObj<RubyService>(
      'RubyService',
      ['fetchGemVersions', 'deleteGem', 'deleteGemVersion'],
      { repoChanges },
    );
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    dangerModalService = new DangerModalService();
    route = { snapshot: { paramMap: convertToParamMap({ gem: 'rails' }) } } as ActivatedRoute;
    component = new RubyGemsVersionListComponent(
      route,
      { username: 'alice' } as AuthService,
      rubyService,
      toastService,
      dangerModalService,
      router,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: rubyService.fetchGemVersions,
      args: { search: 1, sort: 2, page: 3 },
      respond: (content, totalPages) =>
        rubyService.fetchGemVersions.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => rubyService.fetchGemVersions.and.returnValue(throwError(() => 'boom')),
      security: { watch: securityService.watchVersionSecuritySummary, argsFor: (repoName) => [repoName, 'rails'] },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, {
      security: true,
      sortResetsPage: true,
      search: { typed: '7.1', loaded: '7.1' },
    }));

  describe('the listing', () => {
    it('loads the versions of the gem in the route', fakeAsync(() => {
      build().respond([VERSION], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.gemName).toBe('rails');
      expect(rubyService.fetchGemVersions).toHaveBeenCalledOnceWith('rails', '', component.sortOption, 0, 10);
      expect(component.versions).toEqual([VERSION]);
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
      removeVersion: rubyService.deleteGemVersion,
      removeVersionArgs: ['rails', '7.1.0', 'ruby'],
      removePackage: rubyService.deleteGem,
      removePackageArgs: ['rails'],
      navigate: router.navigate,
      navigateArgs: [['..'], { relativeTo: route }],
    }));
  });

  describe('deleting from a later page or under a search (RPS-1340)', () => {
    describePagedDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      remove: rubyService.deleteGemVersion,
      invoke: () => component.deleteVersion(VERSION),
      navigate: router.navigate,
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
