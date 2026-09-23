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

import { PypiPackageListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { PypiService } from '../../service/pypi.service';
import { PypiPackagesListComponent } from './pypi-packages-list.component';

const ITEM_UNDER_TEST = { name: 'requests' } as PypiPackageListItem;

describe('PypiPackagesListComponent', () => {
  let component: PypiPackagesListComponent;
  let service: jasmine.SpyObj<PypiService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    service = jasmine.createSpyObj<PypiService>('PypiService', ['fetchRepositoryPackagesLikeName', 'deletePackage'], {
      repoChanges,
    });
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchArtifactSecuritySummary']);
    securityService.watchArtifactSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new PypiPackagesListComponent(
      { username: 'alice' } as AuthService,
      service,
      toastService,
      dangerModalService,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: service.fetchRepositoryPackagesLikeName,
      args: { search: 0, sort: 1, page: 2 },
      respond: (content, totalPages) =>
        service.fetchRepositoryPackagesLikeName.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => service.fetchRepositoryPackagesLikeName.and.returnValue(throwError(() => 'boom')),
      security: { watch: securityService.watchArtifactSecuritySummary, argsFor: (repoName) => [repoName] },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, {
      security: true,
      search: { typed: 'acme', loaded: 'acme' },
    }));

  describe('deletePackage', () => {
    describeSimpleDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: service.deletePackage,
      invoke: () => component.deletePackage(ITEM_UNDER_TEST),
      title: 'Delete Package',
      message: 'Package deleted successfully',
      removeArgs: ['requests'],
    }));
  });

  describe('helpers', () => {
    beforeEach(() => build());

    it('exposes the signed-in username', () => {
      expect(component.username).toBe('alice');
    });

    it('packageRoute links to the item inside the active repository', fakeAsync(() => {
      service.fetchRepositoryPackagesLikeName.and.returnValue(of(pageOf([], 0) as never));
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.packageRoute(ITEM_UNDER_TEST)).toBe(`/${REPO_NAME}/requests`);
    }));

    it('timeAgo renders a relative time', () => {
      expect(component.timeAgo(moment().subtract(3, 'days').toDate() as never)).toBe('3 days ago');
    });

    it('openConfig toggles the config panel', () => {
      component.openConfig(true);
      expect(component.showConfig).toBeTrue();
      component.openConfig(false);
      expect(component.showConfig).toBeFalse();
    });
  });
});
