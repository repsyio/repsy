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
    helmService = jasmine.createSpyObj<HelmService>('HelmService', ['getChartVersions', 'deleteChart'], {
      repoChanges,
    });
    helmService.getChartVersions.and.returnValue(of([MIDDLE, OLD, NEW]));
    helmService.deleteChart.and.returnValue(of(undefined));
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    dangerModalService = new DangerModalService();
    route = { snapshot: { paramMap: convertToParamMap({ name: 'nginx' }) } } as ActivatedRoute;
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
      expect(helmService.getChartVersions).not.toHaveBeenCalled();
    });
  });

  describe('when a repository is selected', () => {
    it('loads all versions of the chart in the route, newest first', fakeAsync(() => {
      selectRepo();

      expect(helmService.getChartVersions).toHaveBeenCalledOnceWith('nginx');
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

      expect(helmService.getChartVersions).not.toHaveBeenCalled();
    }));

    it('stops loading without a toast of its own when the versions cannot be loaded (the interceptor shows it)', fakeAsync(() => {
      helmService.getChartVersions.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 404, error: { text: 'Chart not found.' } })),
      );

      selectRepo();

      // RPS-1302: the error is an HttpErrorResponse, so toasting it read "[object Object]".
      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
      expect(component.versions).toEqual([]);
    }));

    it('refreshPage loads the versions again', fakeAsync(() => {
      selectRepo();
      helmService.getChartVersions.and.returnValue(of([OLD]));

      component.refreshPage();

      expect(component.versions).toEqual([OLD]);
    }));
  });

  describe('searching and sorting', () => {
    beforeEach(fakeAsync(() => selectRepo()));

    it('search keeps the versions that contain the text, ignoring case', () => {
      component.search('1.');

      expect(component.versions).toEqual([MIDDLE, OLD].sort((a, b) => b.createdAt.localeCompare(a.createdAt)));

      component.search('');
      expect(component.versions.length).toBe(3);
    });

    it('search matches on any part of the version, case-insensitively', () => {
      helmService.getChartVersions.and.returnValue(of([version('1.0.0-RC1', OLD.createdAt), NEW]));
      component.refreshPage();

      component.search('rc');

      expect(component.versions.map((v) => v.version)).toEqual(['1.0.0-RC1']);
    });

    it('sort orders by creation time, oldest first for ascending', () => {
      component.sort({ name: 'Oldest', column: 'createdAt', type: 'ASC' });

      expect(component.versions).toEqual([OLD, MIDDLE, NEW]);
      expect(component.sortOption.type).toBe('ASC');
    });

    it('sort keeps the search filter', () => {
      component.search('1.');

      component.sort({ name: 'Oldest', column: 'createdAt', type: 'ASC' });

      expect(component.versions).toEqual([OLD, MIDDLE]);
    });

    it('offers newest and oldest as sort options', () => {
      expect(component.sortOptions).toEqual([
        { name: 'Newest', column: 'createdAt', type: 'DESC' },
        { name: 'Oldest', column: 'createdAt', type: 'ASC' },
      ]);
      expect(component.sortOptions).toContain(component.sortOption);
    });
  });

  describe('paging', () => {
    /** Twelve versions, 1.0.0 (oldest) to 1.0.11 (newest). */
    const twelve = Array.from({ length: 12 }, (_, i) =>
      version(`1.0.${i}`, `2026-01-${String(i + 1).padStart(2, '0')}T00:00:00Z`),
    );

    beforeEach(() => helmService.getChartVersions.and.returnValue(of(twelve)));

    it('shows ten versions on the first page and the rest on the second (RPS-1262)', fakeAsync(() => {
      selectRepo();

      expect(component.totalPages).toBe(2);
      expect(component.pageNum).toBe(0);
      expect(component.versions.map((v) => v.version)).toEqual([
        '1.0.11',
        '1.0.10',
        '1.0.9',
        '1.0.8',
        '1.0.7',
        '1.0.6',
        '1.0.5',
        '1.0.4',
        '1.0.3',
        '1.0.2',
      ]);

      component.loadPage(1);

      expect(component.pageNum).toBe(1);
      expect(component.versions.map((v) => v.version)).toEqual(['1.0.1', '1.0.0']);
    }));

    it('has a single page for up to ten versions', fakeAsync(() => {
      selectRepo();
      helmService.getChartVersions.and.returnValue(of(twelve.slice(0, 10)));
      component.refreshPage();
      flushMicrotasks();

      expect(component.totalPages).toBe(1);
      expect(component.versions.length).toBe(10);
    }));

    it('goes back to the first page when the search or the sort changes', fakeAsync(() => {
      selectRepo();
      component.loadPage(1);

      component.search('1.0.1');
      expect(component.pageNum).toBe(0);
      expect(component.versions.map((v) => v.version)).toEqual(['1.0.11', '1.0.10', '1.0.1']);
      expect(component.totalPages).toBe(1);

      component.search('');
      component.loadPage(1);
      component.sort(component.sortOptions[1]);
      expect(component.pageNum).toBe(0);
      expect(component.versions[0].version).toBe('1.0.0');
    }));

    it('stays on the last page that still exists after a refresh returns fewer versions', fakeAsync(() => {
      selectRepo();
      component.loadPage(1);

      helmService.getChartVersions.and.returnValue(of(twelve.slice(0, 5)));
      component.refreshPage();
      flushMicrotasks();

      expect(component.pageNum).toBe(0);
      expect(component.versions.length).toBe(5);
    }));

    it('keeps the listing when a version is deleted while other versions remain on other pages', fakeAsync(() => {
      selectRepo();
      component.loadPage(1);
      component.deleteVersion(component.versions[0]);

      dangerModalService.call();

      expect(helmService.deleteChart).toHaveBeenCalledOnceWith('nginx', '1.0.1');
      expect(router.navigate).not.toHaveBeenCalled();
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
      helmService.getChartVersions.calls.reset();
      component.deleteVersion(OLD);

      dangerModalService.call();

      expect(helmService.deleteChart).toHaveBeenCalledOnceWith('nginx', '1.0.0');
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
      expect(helmService.getChartVersions).toHaveBeenCalledTimes(1);
      expect(router.navigate).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    }));

    it('leaves the listing when the only version is deleted', fakeAsync(() => {
      helmService.getChartVersions.and.returnValue(of([OLD]));
      selectRepo();
      helmService.getChartVersions.calls.reset();
      component.deleteVersion(OLD);

      dangerModalService.call();

      expect(helmService.deleteChart).toHaveBeenCalledOnceWith('nginx', '1.0.0');
      expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
      expect(helmService.getChartVersions).not.toHaveBeenCalled();
    }));

    it('neither toasts, navigates nor reloads when the delete fails', fakeAsync(() => {
      selectRepo();
      helmService.getChartVersions.calls.reset();
      helmService.deleteChart.and.returnValue(throwError(() => 'boom'));
      component.deleteVersion(OLD);

      dangerModalService.call();

      expect(toastService.show).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(helmService.getChartVersions).not.toHaveBeenCalled();
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
      expect(helmService.getChartVersions).toHaveBeenCalledTimes(1);
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
