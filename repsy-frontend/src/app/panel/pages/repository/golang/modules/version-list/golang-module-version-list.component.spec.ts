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
import { renderComponent } from '../../../testing/render-spec-helpers';
import {
  describeEmptyingDelete,
  describeRepoListBehavior,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { GolangService } from '../../service/golang.service';
import { GolangModuleVersionListComponent } from './golang-module-version-list.component';

describe('GolangModuleVersionListComponent', () => {
  let component: GolangModuleVersionListComponent;
  let golangService: jasmine.SpyObj<GolangService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;
  const VERSION = { version: 'v1.2.3' } as Parameters<GolangModuleVersionListComponent['deleteVersion']>[0];

  function build(modulePath: string | null = 'github.com/acme/lib'): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    golangService = jasmine.createSpyObj<GolangService>(
      'GolangService',
      ['fetchModuleVersions', 'deleteModuleVersion'],
      {
        repoChanges,
      },
    );
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']);
    dangerModalService = new DangerModalService();
    component = new GolangModuleVersionListComponent(
      {
        snapshot: { queryParamMap: convertToParamMap(modulePath ? { modulePath } : {}) },
      } as ActivatedRoute,
      router,
      golangService,
      toastService,
      dangerModalService,
      { username: 'alice' } as AuthService,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: golangService.fetchModuleVersions,
      args: { search: 1, sort: 2, page: 3 },
      respond: (content, totalPages) =>
        golangService.fetchModuleVersions.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => golangService.fetchModuleVersions.and.returnValue(throwError(() => 'boom')),
      security: {
        watch: securityService.watchVersionSecuritySummary,
        argsFor: (repoName) => [repoName, 'github.com/acme/lib'],
      },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, { security: true, search: { typed: 'v1.2', loaded: 'v1.2' } }));

  describe('the listing', () => {
    it('loads the versions of the module in the modulePath query parameter', fakeAsync(() => {
      build().respond([VERSION], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.modulePath).toBe('github.com/acme/lib');
      expect(golangService.fetchModuleVersions).toHaveBeenCalledOnceWith(
        'github.com/acme/lib',
        '',
        component.sortOption,
        0,
        10,
      );
    }));

    it('goes back to the repository without loading anything when the module path is missing', fakeAsync(() => {
      build(null);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(router.navigate).toHaveBeenCalledOnceWith([`/${REPO_NAME}`]);
      expect(golangService.fetchModuleVersions).not.toHaveBeenCalled();
      expect(securityService.watchVersionSecuritySummary).not.toHaveBeenCalled();
    }));
  });

  describe('deleteVersion', () => {
    describeEmptyingDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: golangService.deleteModuleVersion,
      invoke: () => component.deleteVersion(VERSION),
      title: 'Delete Version',
      message: 'Version deleted successfully',
      removeArgs: ['github.com/acme/lib', 'v1.2.3'],
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

describe('GolangModuleVersionListComponent template', () => {
  async function render(content: unknown[], totalPages: number): Promise<HTMLElement> {
    const golangService = jasmine.createSpyObj<GolangService>('GolangService', ['fetchModuleVersions'], {
      repoChanges: new BehaviorSubject<RepoPermissionInfo | null>(permission(REPO_NAME, { canManage: true })),
    });
    golangService.fetchModuleVersions.and.returnValue(of(pageOf(content, totalPages) as never));
    const securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));

    const { el } = await renderComponent(GolangModuleVersionListComponent, [
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap({ modulePath: 'github.com/acme/lib' }) } },
      },
      { provide: AuthService, useValue: { username: 'alice' } },
      { provide: GolangService, useValue: golangService },
      { provide: SecurityService, useValue: securityService },
    ]);
    return el;
  }

  it('renders no pager under the empty state (RPS-1262: it printed "1 NaN")', async () => {
    const el = await render([], 0);

    expect(el.querySelector('app-empty-list')).not.toBeNull();
    expect(el.querySelector('app-pagination')).toBeNull();
  });

  it('renders the pager under a page of versions', async () => {
    const el = await render([{ id: 'v1', version: 'v1.0.0' }], 3);

    expect(el.querySelector('app-empty-list')).toBeNull();
    expect(el.querySelector('app-pagination')).not.toBeNull();
    expect(el.querySelector('[data-testid="pagination"]')?.classList).not.toContain('hidden');
  });
});
