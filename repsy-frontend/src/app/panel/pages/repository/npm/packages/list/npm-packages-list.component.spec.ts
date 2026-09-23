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

import { NpmPackageListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { NpmService } from '../../service/npm.service';
import { NpmPackagesListComponent } from './npm-packages-list.component';

const SCOPED = { name: 'ui', scope: 'acme' } as NpmPackageListItem;
const UNSCOPED = { name: 'left-pad' } as NpmPackageListItem;

describe('NpmPackagesListComponent', () => {
  let component: NpmPackagesListComponent;
  let npmService: jasmine.SpyObj<NpmService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    npmService = jasmine.createSpyObj<NpmService>('NpmService', ['searchPackages', 'deletePackage'], { repoChanges });
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchArtifactSecuritySummary']);
    securityService.watchArtifactSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new NpmPackagesListComponent(
      npmService,
      { username: 'alice' } as AuthService,
      toastService,
      dangerModalService,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: npmService.searchPackages,
      args: { search: 0, sort: 1, page: 2 },
      respond: (content, totalPages) =>
        npmService.searchPackages.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => npmService.searchPackages.and.returnValue(throwError(() => 'boom')),
      security: { watch: securityService.watchArtifactSecuritySummary, argsFor: (repoName) => [repoName] },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, {
      security: true,
      search: { typed: 'acme', loaded: 'acme' },
    }));

  describe('search', () => {
    beforeEach(() => build());

    it('drops the leading @ of a scope the user typed', () => {
      npmService.searchPackages.and.returnValue(of(pageOf([], 0) as never));

      component.search('@acme');

      expect(component.searchText).toBe('acme');
      expect(npmService.searchPackages.calls.mostRecent().args[0]).toBe('acme');
    });

    it('sends null instead of an empty search text', () => {
      npmService.searchPackages.and.returnValue(of(pageOf([], 0) as never));

      component.search('');

      expect(npmService.searchPackages.calls.mostRecent().args[0]).toBeNull();
    });
  });

  describe('deletePackage', () => {
    describeSimpleDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: npmService.deletePackage,
      invoke: () => component.deletePackage(SCOPED),
      title: 'Delete Package',
      message: 'Package deleted successfully',
      removeArgs: ['ui', 'acme'],
    }));
  });

  describe('helpers', () => {
    beforeEach(() => {
      build();
      npmService.searchPackages.and.returnValue(of(pageOf([], 0) as never));
    });

    it('exposes the signed-in username', () => {
      expect(component.username).toBe('alice');
    });

    it('packageRoute puts an unscoped package under ~ and a scoped one under its scope', fakeAsync(() => {
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.packageRoute(SCOPED)).toBe(`/${REPO_NAME}/acme/ui`);
      expect(component.packageRoute(UNSCOPED)).toBe(`/${REPO_NAME}/~/left-pad`);
    }));

    it('packageSecurityKey prefixes the scope with @', () => {
      expect(component.packageSecurityKey(SCOPED)).toBe('@acme/ui');
      expect(component.packageSecurityKey(UNSCOPED)).toBe('left-pad');
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
