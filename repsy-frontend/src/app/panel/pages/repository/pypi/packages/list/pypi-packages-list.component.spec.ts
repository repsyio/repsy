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
import { renderComponent } from '../../../testing/render-spec-helpers';
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

describe('PypiPackagesListComponent template', () => {
  async function render(flags: Parameters<typeof permission>[1]): Promise<HTMLElement> {
    const service = jasmine.createSpyObj<PypiService>('PypiService', ['fetchRepositoryPackagesLikeName'], {
      repoChanges: new BehaviorSubject<RepoPermissionInfo | null>(permission(REPO_NAME, flags)),
    });
    service.fetchRepositoryPackagesLikeName.and.returnValue(
      of(pageOf([{ name: 'requests', latestVersion: '2.0.0', stableVersion: '1.0.0' }], 1) as never),
    );
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchArtifactSecuritySummary']);
    securityService.watchArtifactSecuritySummary.and.returnValue(of({}));

    const { el } = await renderComponent(PypiPackagesListComponent, [
      { provide: AuthService, useValue: { username: 'alice' } },
      { provide: PypiService, useValue: service },
      { provide: SecurityService, useValue: securityService },
    ]);
    return el;
  }

  const cardMenu = '[data-testid="pkg-list-card-requests"] [data-testid="row-menu"]';
  const rowMenu = '[data-testid="pkg-list-row-requests"] [data-testid="row-menu"]';

  it('offers Delete on the mobile card exactly where it does on the desktop row: to a manager (RPS-1262)', async () => {
    const manager = await render({ canWrite: true, canManage: true });
    expect(manager.querySelector(rowMenu)).not.toBeNull();
    expect(manager.querySelector(cardMenu)).not.toBeNull();
  });

  it('offers no Delete on the mobile card to a user who can write but not manage (RPS-1262)', async () => {
    const writer = await render({ canWrite: true, canManage: false });
    expect(writer.querySelector(rowMenu)).toBeNull();
    expect(writer.querySelector(cardMenu)).toBeNull();
  });

  it('shows the latest VERSION in the Latest link of the mobile card (RPS-1261)', async () => {
    const el = await render({ canManage: true });

    const latest = el.querySelector('[data-testid="pkg-list-card-requests"] [data-testid="row-latest-link"]');
    expect(latest?.textContent?.trim()).toBe('2.0.0');
  });
});
