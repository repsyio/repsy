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

import { NpmPackageListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { renderComponent } from '../../../testing/render-spec-helpers';
import {
  describeEmptyingDelete,
  describeRepoListBehavior,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { NpmService } from '../../service/npm.service';
import { NpmPackagesScopeFilterComponent } from './npm-packages-scoped-list.component';

const PACKAGE = { name: 'ui', scope: 'acme' } as NpmPackageListItem;

describe('NpmPackagesScopeFilterComponent', () => {
  let component: NpmPackagesScopeFilterComponent;
  let npmService: jasmine.SpyObj<NpmService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(scope = 'acme'): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    npmService = jasmine.createSpyObj<NpmService>(
      'NpmService',
      ['searchScopedPackages', 'searchUnscopedPackages', 'deletePackage'],
      { repoChanges },
    );
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    dangerModalService = new DangerModalService();
    component = new NpmPackagesScopeFilterComponent(
      { snapshot: { paramMap: convertToParamMap({ scope }) } } as ActivatedRoute,
      npmService,
      toastService,
      dangerModalService,
      router,
    );
    const load = scope === '~' ? npmService.searchUnscopedPackages : npmService.searchScopedPackages;
    return {
      component,
      repoChanges,
      load,
      args: scope === '~' ? { search: 0, sort: 1, page: 2 } : { search: 1, sort: 2, page: 3 },
      respond: (content, totalPages) => load.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => load.and.returnValue(throwError(() => 'boom')),
    };
  }

  describe('a scope', () => {
    describeRepoListBehavior(build, { security: false, search: { typed: 'ui', loaded: 'ui' } });

    it('is listed with the scope from the route', fakeAsync(() => {
      build('acme').respond([], 0);
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(npmService.searchScopedPackages).toHaveBeenCalledOnceWith('acme', '', component.sortOption, 0, 10);
      expect(npmService.searchUnscopedPackages).not.toHaveBeenCalled();
    }));
  });

  describe('the unscoped packages (~)', () => {
    describeRepoListBehavior(() => build('~'), { security: false, search: { typed: 'ui', loaded: 'ui' } });

    it('are listed through the unscoped search, without a scope', fakeAsync(() => {
      build('~').respond([], 0);
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(npmService.searchUnscopedPackages).toHaveBeenCalledOnceWith('', component.sortOption, 0, 10);
      expect(npmService.searchScopedPackages).not.toHaveBeenCalled();
    }));
  });

  describe('deletePackage', () => {
    describeEmptyingDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: npmService.deletePackage,
      invoke: () => component.deletePackage(PACKAGE),
      title: 'Delete Package',
      message: 'Package deleted successfully',
      removeArgs: ['ui', 'acme'],
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

describe('NpmPackagesScopeFilterComponent template', () => {
  async function render(flags: Parameters<typeof permission>[1]): Promise<HTMLElement> {
    const npmService = jasmine.createSpyObj<NpmService>('NpmService', ['searchScopedPackages'], {
      repoChanges: new BehaviorSubject<RepoPermissionInfo | null>(permission(REPO_NAME, flags)),
    });
    npmService.searchScopedPackages.and.returnValue(of(pageOf([PACKAGE], 1) as never));

    const { el } = await renderComponent(NpmPackagesScopeFilterComponent, [
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ scope: 'acme' }) } } },
      { provide: NpmService, useValue: npmService },
    ]);
    return el;
  }

  const cardMenu = '[data-testid="pkg-sublist-card-ui"] [data-testid="row-menu"]';

  it('offers Delete on the mobile card to a manager', async () => {
    expect((await render({ canWrite: true, canManage: true })).querySelector(cardMenu)).not.toBeNull();
  });

  it('offers no Delete on the mobile card to a user who can write but not manage (RPS-1262)', async () => {
    expect((await render({ canWrite: true, canManage: false })).querySelector(cardMenu)).toBeNull();
  });
});
