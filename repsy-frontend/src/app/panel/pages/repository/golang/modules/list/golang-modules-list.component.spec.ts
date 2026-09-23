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
import moment from 'moment';
import { BehaviorSubject, of, throwError } from 'rxjs';

import { GoModuleListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { SecurityService } from '../../../../security/service/security.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import {
  describeRepoListBehavior,
  describeSimpleDelete,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { GolangService } from '../../service/golang.service';
import { GolangModulesListComponent } from './golang-modules-list.component';

const MODULE = { modulePath: 'github.com/acme/lib' } as GoModuleListItem;

describe('GolangModulesListComponent', () => {
  let component: GolangModulesListComponent;
  let golangService: jasmine.SpyObj<GolangService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    golangService = jasmine.createSpyObj<GolangService>(
      'GolangService',
      ['fetchModules', 'searchModules', 'deleteModule'],
      { repoChanges },
    );
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchArtifactSecuritySummary']);
    securityService.watchArtifactSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new GolangModulesListComponent(
      { username: 'alice' } as AuthService,
      golangService,
      toastService,
      dangerModalService,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: golangService.fetchModules,
      args: { sort: 0, page: 1 },
      respond: (content, totalPages) =>
        golangService.fetchModules.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => golangService.fetchModules.and.returnValue(throwError(() => 'boom')),
      security: { watch: securityService.watchArtifactSecuritySummary, argsFor: (repoName) => [repoName] },
    };
  }

  // Without search text the listing comes from fetchModules; the search text switches it to searchModules (below).
  describe('shared list behavior', () => describeRepoListBehavior(build, { security: true }));

  describe('searching', () => {
    beforeEach(fakeAsync(() => {
      build();
      golangService.fetchModules.and.returnValue(of(pageOf([], 1) as never));
      golangService.searchModules.and.returnValue(of(pageOf([], 1) as never));
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();
      golangService.fetchModules.calls.reset();
    }));

    it('switches to the search endpoint with the text, from the first page', fakeAsync(() => {
      component.loadPage(2);
      flushMicrotasks();

      component.search('acme');
      flushMicrotasks();

      expect(component.pageNum).toBe(0);
      expect(golangService.searchModules).toHaveBeenCalledOnceWith('acme', component.sortOption, 0, 10);
      expect(golangService.fetchModules).toHaveBeenCalledTimes(1);
    }));

    it('keeps searching while paging and sorting', fakeAsync(() => {
      component.search('acme');
      flushMicrotasks();
      const oldest = component.sortOptions[1];

      component.sort(oldest);
      flushMicrotasks();
      component.loadPage(1);
      flushMicrotasks();

      expect(golangService.searchModules.calls.allArgs()).toEqual([
        ['acme', component.sortOptions[0], 0, 10],
        ['acme', oldest, 0, 10],
        ['acme', oldest, 1, 10],
      ]);
    }));

    it('goes back to the plain listing when the search text is cleared', fakeAsync(() => {
      component.search('acme');
      flushMicrotasks();
      golangService.fetchModules.calls.reset();

      component.search('');
      flushMicrotasks();

      expect(golangService.fetchModules).toHaveBeenCalledOnceWith(component.sortOption, 0, 10);
    }));
  });

  describe('deleteModule', () => {
    describeSimpleDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: golangService.deleteModule,
      invoke: () => component.deleteModule(MODULE),
      title: 'Delete Module',
      message: 'Module deleted successfully',
      removeArgs: ['github.com/acme/lib'],
    }));
  });

  describe('helpers', () => {
    beforeEach(() => build());

    it('exposes the signed-in username', () => {
      expect(component.username).toBe('alice');
    });

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
