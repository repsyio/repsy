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

import { HttpErrorResponse } from '@angular/common/http';
import { fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import moment from 'moment';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';

import { HelmChartVersionItem, RepoPermissionInfo, VersionSecuritySummary } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { PagedData } from '../../../../../shared/dto/paged-data';
import { Sort } from '../../../../../shared/dto/sort';
import { SecurityService } from '../../../../security/service/security.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { REPO_NAME } from '../../../testing/repo-list-spec-helpers';
import { HelmService } from '../../service/helm.service';
import { HelmChartsVersionListComponent } from './helm-charts-version-list.component';

function version(name: string, createdAt: string): HelmChartVersionItem {
  return { version: name, createdAt };
}

const OLD = version('1.0.0', '2026-01-01T00:00:00Z');
const MIDDLE = version('1.1.0', '2026-02-01T00:00:00Z');
const NEW = version('2.0.0', '2026-03-01T00:00:00Z');

/** One server page: the items plus the paging block the backend sends. */
function page(
  items: HelmChartVersionItem[],
  totalElements = items.length,
  number = 0,
): PagedData<HelmChartVersionItem> {
  return {
    content: items,
    page: { size: 10, number, totalElements, totalPages: Math.max(1, Math.ceil(totalElements / 10)) },
  } as PagedData<HelmChartVersionItem>;
}

const NEWEST_FIRST: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };
const OLDEST_FIRST: Sort = { name: 'Oldest', column: 'createdAt', type: 'ASC' };

describe('HelmChartsVersionListComponent', () => {
  let component: HelmChartsVersionListComponent;
  let route: ActivatedRoute;
  let helmService: jasmine.SpyObj<HelmService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  beforeEach(() => {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    helmService = jasmine.createSpyObj<HelmService>('HelmService', ['fetchChartVersions', 'deleteChart'], {
      repoChanges,
    });
    helmService.fetchChartVersions.and.returnValue(of(page([NEW, MIDDLE, OLD])));
    helmService.deleteChart.and.returnValue(of(undefined));
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    dangerModalService = new DangerModalService();
    route = { snapshot: { paramMap: convertToParamMap({ packageName: 'nginx' }) } } as ActivatedRoute;
    component = new HelmChartsVersionListComponent(
      route,
      { username: 'alice' } as AuthService,
      helmService,
      toastService,
      dangerModalService,
      router,
      securityService,
    );
  });

  afterEach(() => component.ngOnDestroy());

  function selectRepo(canManage = true): void {
    repoChanges.next(permission(REPO_NAME, { canManage }));
    flushMicrotasks();
  }

  describe('before a repository is selected', () => {
    it('starts loading, with no permissions, and fetches nothing', () => {
      expect(component.loading).toBeTrue();
      expect(component.canManage).toBeFalse();
      expect(component.versions).toEqual([]);
      expect(helmService.fetchChartVersions).not.toHaveBeenCalled();
    });
  });

  describe('when a repository is selected', () => {
    it('asks the server for the first page of the chart in the route, newest first', fakeAsync(() => {
      selectRepo();

      expect(helmService.fetchChartVersions).toHaveBeenCalledOnceWith('nginx', '', NEWEST_FIRST, 0, 10);
      expect(component.chartName).toBe('nginx');
      expect(component.versions).toEqual([NEW, MIDDLE, OLD]);
      expect(component.loading).toBeFalse();
      expect(component.error).toBeNull();
    }));

    it('lets only a repository manager manage', fakeAsync(() => {
      selectRepo(true);
      expect(component.canManage).toBeTrue();

      selectRepo(false);
      expect(component.canManage).toBeFalse();
    }));

    it('ignores an empty repository value', fakeAsync(() => {
      repoChanges.next(null);
      flushMicrotasks();

      expect(helmService.fetchChartVersions).not.toHaveBeenCalled();
    }));

    it('stops loading without a toast of its own when the versions cannot be loaded (the interceptor shows it)', fakeAsync(() => {
      helmService.fetchChartVersions.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 404, error: { detail: 'Chart not found.' } })),
      );

      selectRepo();

      // RPS-1302: the error is an HttpErrorResponse, so toasting it read "[object Object]".
      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
      expect(component.versions).toEqual([]);
    }));

    it('refreshPage loads the versions again', fakeAsync(() => {
      selectRepo();
      helmService.fetchChartVersions.and.returnValue(of(page([OLD])));

      component.refreshPage();

      expect(component.versions).toEqual([OLD]);
    }));
  });

  describe('searching and sorting (done by the server)', () => {
    beforeEach(fakeAsync(() => {
      selectRepo();
      helmService.fetchChartVersions.calls.reset();
    }));

    it('search sends the text and shows what the server returns', () => {
      helmService.fetchChartVersions.and.returnValue(of(page([OLD])));

      component.search('rc');

      expect(helmService.fetchChartVersions).toHaveBeenCalledOnceWith('nginx', 'rc', NEWEST_FIRST, 0, 10);
      expect(component.searchText).toBe('rc');
      expect(component.versions).toEqual([OLD]);
    });

    it('sort sends the column and direction', () => {
      component.sort(OLDEST_FIRST);

      expect(helmService.fetchChartVersions).toHaveBeenCalledOnceWith('nginx', '', OLDEST_FIRST, 0, 10);
      expect(component.sortOption.type).toBe('ASC');
    });

    it('sort keeps the search text', () => {
      component.search('1.');
      component.sort(OLDEST_FIRST);

      expect(helmService.fetchChartVersions).toHaveBeenCalledWith('nginx', '1.', OLDEST_FIRST, 0, 10);
    });

    it('offers newest and oldest as sort options', () => {
      expect(component.sortOptions).toEqual([NEWEST_FIRST, OLDEST_FIRST]);
      expect(component.sortOptions).toContain(component.sortOption);
    });
  });

  describe('paging (done by the server)', () => {
    it('takes the page count from the server and asks for the page that is loaded', fakeAsync(() => {
      helmService.fetchChartVersions.and.returnValue(of(page([NEW, MIDDLE], 12)));
      selectRepo();

      expect(component.pagedData.page.totalPages).toBe(2);
      expect(component.pageNum).toBe(0);

      component.loadPage(1);

      expect(component.pageNum).toBe(1);
      expect(helmService.fetchChartVersions).toHaveBeenCalledWith('nginx', '', NEWEST_FIRST, 1, 10);
    }));

    it('goes back to the first page when the search or the sort changes', fakeAsync(() => {
      selectRepo();
      component.loadPage(1);

      component.search('1.0.1');
      expect(component.pageNum).toBe(0);

      component.loadPage(1);
      component.sort(component.sortOptions[1]);
      expect(component.pageNum).toBe(0);
      expect(helmService.fetchChartVersions).toHaveBeenCalledWith('nginx', '1.0.1', OLDEST_FIRST, 0, 10);
    }));

    it('keeps the listing when a version is deleted while other versions remain on other pages', fakeAsync(() => {
      helmService.fetchChartVersions.and.returnValue(of(page([OLD], 11, 1)));
      selectRepo();
      component.loadPage(1);
      component.deleteVersion(component.versions[0]);

      dangerModalService.call();

      expect(helmService.deleteChart).toHaveBeenCalledOnceWith('nginx', '1.0.0');
      expect(router.navigate).not.toHaveBeenCalled();
    }));

    it('steps back a page when the last version of the last page is deleted', fakeAsync(() => {
      helmService.fetchChartVersions.and.returnValue(of(page([OLD], 11, 1)));
      selectRepo();
      component.loadPage(1);
      helmService.fetchChartVersions.calls.reset();
      component.deleteVersion(OLD);

      dangerModalService.call();

      expect(component.pageNum).toBe(0);
      expect(helmService.fetchChartVersions).toHaveBeenCalledOnceWith('nginx', '', NEWEST_FIRST, 0, 10);
    }));
  });

  describe('the security summary', () => {
    it('is watched for the chart and shown as it changes', fakeAsync(() => {
      const watched = new Subject<Record<string, VersionSecuritySummary>>();
      securityService.watchVersionSecuritySummary.and.returnValue(watched);
      selectRepo();
      const summary = { '1.0.0': { scanned: true, findingCount: 1 } as VersionSecuritySummary };

      watched.next(summary);

      expect(securityService.watchVersionSecuritySummary).toHaveBeenCalledOnceWith(REPO_NAME, 'nginx');
      expect(component.securitySummary).toEqual(summary);
    }));

    it('does not fail the page when it cannot be loaded', fakeAsync(() => {
      securityService.watchVersionSecuritySummary.and.returnValue(throwError(() => new Error('boom')));

      selectRepo();

      expect(component.securitySummary).toEqual({});
      expect(component.versions.length).toBe(3);
    }));
  });

  describe('deleteVersion', () => {
    it('asks for confirmation before deleting anything', fakeAsync(() => {
      selectRepo();

      component.deleteVersion(OLD);

      expect(dangerModalService.modal).toEqual({ title: 'Delete Version', action: 'Delete', message: null });
      expect(helmService.deleteChart).not.toHaveBeenCalled();
    }));

    it('deletes the version, toasts and reloads the versions while others remain', fakeAsync(() => {
      selectRepo();
      helmService.fetchChartVersions.calls.reset();
      component.deleteVersion(OLD);

      dangerModalService.call();

      expect(helmService.deleteChart).toHaveBeenCalledOnceWith('nginx', '1.0.0');
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
      expect(helmService.fetchChartVersions).toHaveBeenCalledTimes(1);
      expect(router.navigate).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    }));

    it('leaves the listing when the only version is deleted', fakeAsync(() => {
      helmService.fetchChartVersions.and.returnValue(of(page([OLD])));
      selectRepo();
      helmService.fetchChartVersions.calls.reset();
      component.deleteVersion(OLD);

      dangerModalService.call();

      expect(helmService.deleteChart).toHaveBeenCalledOnceWith('nginx', '1.0.0');
      expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
      expect(helmService.fetchChartVersions).not.toHaveBeenCalled();
    }));

    it('neither toasts, navigates nor reloads when the delete fails', fakeAsync(() => {
      selectRepo();
      helmService.fetchChartVersions.calls.reset();
      helmService.deleteChart.and.returnValue(throwError(() => 'boom'));
      component.deleteVersion(OLD);

      dangerModalService.call();

      expect(toastService.show).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(helmService.fetchChartVersions).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    }));
  });

  describe('ngOnDestroy', () => {
    it('stops following repository changes and stops watching the security summary', fakeAsync(() => {
      const watched = new Subject<Record<string, VersionSecuritySummary>>();
      securityService.watchVersionSecuritySummary.and.returnValue(watched);
      selectRepo();
      expect(watched.observed).toBeTrue();

      component.ngOnDestroy();
      selectRepo();

      expect(watched.observed).toBeFalse();
      expect(helmService.fetchChartVersions).toHaveBeenCalledTimes(1);
    }));
  });

  describe('helpers', () => {
    it('timeAgo renders a relative time', () => {
      expect(component.timeAgo(moment().subtract(3, 'days').toISOString())).toBe('3 days ago');
    });

    it('openConfig toggles the config panel', () => {
      component.openConfig(true);
      expect(component.showConfig).toBeTrue();
      component.openConfig(false);
      expect(component.showConfig).toBeFalse();
    });
  });
});
