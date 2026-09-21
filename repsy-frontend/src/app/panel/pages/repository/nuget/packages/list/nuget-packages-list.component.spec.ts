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
///

import { fakeAsync, flushMicrotasks } from '@angular/core/testing';
import moment from 'moment';
import { BehaviorSubject, of, throwError } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { VersionSecuritySummary } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { PagedData } from '../../../../../shared/dto/paged-data';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { Sort } from '../../../../../shared/dto/sort';
import { SecurityService } from '../../../../security/service/security.service';
import { NugetPackageListItem } from '../../dto/nuget-package-list-item';
import { NugetService } from '../../service/nuget.service';
import { NugetPackagesListComponent } from './nuget-packages-list.component';

const REPO = 'nuget-repo';
const DEFAULT_SORT: Sort = { name: 'Name (A-Z)', column: 'packageId', type: 'ASC' };
const SECURITY_SUMMARY: Record<string, VersionSecuritySummary> = {
  'Acme.Lib': { scanned: true, findingCount: 1 } as VersionSecuritySummary,
};

function item(packageId: string): NugetPackageListItem {
  return Object.assign(new NugetPackageListItem(), { packageId, latestVersion: '1.0.0', totalDownloads: 0 });
}

function pageOf(content: NugetPackageListItem[], totalPages: number): PagedData<NugetPackageListItem> {
  const paged = new PagedData<NugetPackageListItem>();
  paged.content = content;
  paged.page = { number: 0, size: 10, totalElements: content.length, totalPages };
  return paged;
}

function repo(canManage: boolean): RepoPermissionInfo {
  return Object.assign(new RepoPermissionInfo(), { repoName: REPO, canRead: true, canWrite: canManage, canManage });
}

describe('NugetPackagesListComponent', () => {
  let component: NugetPackagesListComponent;
  let nugetService: jasmine.SpyObj<NugetService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  beforeEach(() => {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    nugetService = jasmine.createSpyObj<NugetService>('NugetService', ['fetchRepositoryPackages', 'deletePackage'], {
      repoChanges,
    });
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['getArtifactSecuritySummary']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();

    nugetService.fetchRepositoryPackages.and.resolveTo(pageOf([item('Acme.Lib'), item('Acme.Web')], 3));
    nugetService.deletePackage.and.resolveTo(undefined);
    securityService.getArtifactSecuritySummary.and.returnValue(of(SECURITY_SUMMARY));

    component = new NugetPackagesListComponent(
      { username: 'alice' } as AuthService,
      nugetService,
      toastService,
      dangerModalService,
      securityService,
    );
  });

  afterEach(() => component.ngOnDestroy());

  /** Selects the repository and lets the package fetch settle. */
  function selectRepo(canManage = true): void {
    repoChanges.next(repo(canManage));
    flushMicrotasks();
  }

  describe('before a repository is selected', () => {
    it('starts loading, with no permissions and nothing fetched', () => {
      expect(component.loading).toBeTrue();
      expect(component.canManage).toBeFalse();
      expect(component.totalPages).toBe(0);
      expect(component.packages).toEqual([]);
      expect(component.sortOption).toEqual(DEFAULT_SORT);
      expect(nugetService.fetchRepositoryPackages).not.toHaveBeenCalled();
    });

    it('exposes the repository base URL and the signed-in username', () => {
      expect(component.baseUrl).toBe(environment.repoBaseUrl);
      expect(component.username).toBe('alice');
    });
  });

  describe('when a repository is selected', () => {
    it('fetches the first page of packages, sorted by name, ten at a time', fakeAsync(() => {
      selectRepo();

      expect(nugetService.fetchRepositoryPackages).toHaveBeenCalledOnceWith('', DEFAULT_SORT, 0, 10);
      expect(component.packages.map((p) => p.packageId)).toEqual(['Acme.Lib', 'Acme.Web']);
      expect(component.totalPages).toBe(3);
      expect(component.loading).toBeFalse();
      expect(component.error).toBeNull();
    }));

    it('fetches the security summary of the repository', fakeAsync(() => {
      selectRepo();

      expect(securityService.getArtifactSecuritySummary).toHaveBeenCalledOnceWith(REPO);
      expect(component.securitySummary).toEqual(SECURITY_SUMMARY);
    }));

    it('lets only a repository manager manage packages', fakeAsync(() => {
      selectRepo(true);
      expect(component.canManage).toBeTrue();

      selectRepo(false);
      expect(component.canManage).toBeFalse();
    }));

    it('refetches when another repository is selected', fakeAsync(() => {
      selectRepo();
      selectRepo();

      expect(nugetService.fetchRepositoryPackages).toHaveBeenCalledTimes(2);
      expect(securityService.getArtifactSecuritySummary).toHaveBeenCalledTimes(2);
    }));

    it('ignores an empty repository value', fakeAsync(() => {
      repoChanges.next(null);
      flushMicrotasks();

      expect(nugetService.fetchRepositoryPackages).not.toHaveBeenCalled();
    }));

    it('shows the failure and keeps the listing empty when the fetch fails', fakeAsync(() => {
      nugetService.fetchRepositoryPackages.and.rejectWith('Repository not found');

      selectRepo();

      expect(component.error).toBe('Repository not found');
      expect(toastService.show).toHaveBeenCalledOnceWith('Repository not found', 'error');
      expect(component.loading).toBeFalse();
      expect(component.packages).toEqual([]);
    }));

    it('clears an earlier error after a successful fetch', fakeAsync(() => {
      nugetService.fetchRepositoryPackages.and.rejectWith('boom');
      selectRepo();
      nugetService.fetchRepositoryPackages.and.resolveTo(pageOf([item('Acme.Lib')], 1));

      component.refreshPage();
      flushMicrotasks();

      expect(component.error).toBeNull();
      expect(component.packages.length).toBe(1);
    }));

    it('keeps the previous security summary when it cannot be loaded', fakeAsync(() => {
      securityService.getArtifactSecuritySummary.and.returnValue(throwError(() => new Error('boom')));

      selectRepo();

      expect(component.securitySummary).toEqual({});
      expect(toastService.show).not.toHaveBeenCalled();
    }));
  });

  describe('paging, searching and sorting', () => {
    beforeEach(fakeAsync(() => {
      selectRepo();
      nugetService.fetchRepositoryPackages.calls.reset();
    }));

    it('loadPage fetches the requested page', fakeAsync(() => {
      component.loadPage(2);
      flushMicrotasks();

      expect(component.pageNum).toBe(2);
      expect(nugetService.fetchRepositoryPackages).toHaveBeenCalledOnceWith('', DEFAULT_SORT, 2, 10);
    }));

    it('search restarts from the first page with the new text and keeps it for later pages', fakeAsync(() => {
      component.loadPage(2);
      flushMicrotasks();

      component.search('acme');
      flushMicrotasks();

      expect(component.pageNum).toBe(0);
      expect(component.searchText).toBe('acme');
      expect(nugetService.fetchRepositoryPackages.calls.mostRecent().args).toEqual(['acme', DEFAULT_SORT, 0, 10]);

      component.loadPage(1);
      flushMicrotasks();

      expect(nugetService.fetchRepositoryPackages.calls.mostRecent().args).toEqual(['acme', DEFAULT_SORT, 1, 10]);
    }));

    it('sort fetches with the chosen order', fakeAsync(() => {
      const descending: Sort = { name: 'Name (Z-A)', column: 'packageId', type: 'DESC' };

      component.sort(descending);
      flushMicrotasks();

      expect(component.sortOption).toBe(descending);
      expect(nugetService.fetchRepositoryPackages).toHaveBeenCalledOnceWith('', descending, 0, 10);
    }));

    it('offers name ascending and descending as sort options', () => {
      expect(component.sortOptions).toEqual([DEFAULT_SORT, { name: 'Name (Z-A)', column: 'packageId', type: 'DESC' }]);
    });

    it('refreshPage repeats the current query', fakeAsync(() => {
      component.search('acme');
      flushMicrotasks();
      nugetService.fetchRepositoryPackages.calls.reset();

      component.refreshPage();
      flushMicrotasks();

      expect(nugetService.fetchRepositoryPackages).toHaveBeenCalledOnceWith('acme', DEFAULT_SORT, 0, 10);
    }));

    it('shows the loading state while a page is fetched', fakeAsync(() => {
      component.loadPage(1);

      expect(component.loading).toBeTrue();

      flushMicrotasks();

      expect(component.loading).toBeFalse();
    }));
  });

  describe('deletePackage', () => {
    const pkg = item('Acme.Lib');

    beforeEach(fakeAsync(() => {
      selectRepo();
      nugetService.fetchRepositoryPackages.calls.reset();
    }));

    it('asks for confirmation before deleting anything', () => {
      component.deletePackage(pkg);

      expect(dangerModalService.modal).toEqual({ title: 'Delete Package', action: 'Delete', message: null });
      expect(nugetService.deletePackage).not.toHaveBeenCalled();
    });

    it('deletes the package once the modal confirms, then toasts and refreshes the listing', fakeAsync(() => {
      component.deletePackage(pkg);

      dangerModalService.call();

      expect(nugetService.deletePackage).toHaveBeenCalledOnceWith('Acme.Lib');
      expect(component.loading).toBeTrue();

      flushMicrotasks();

      expect(toastService.show).toHaveBeenCalledOnceWith('Package deleted successfully', 'success');
      expect(nugetService.fetchRepositoryPackages).toHaveBeenCalledTimes(1);
      expect(component.loading).toBeFalse();
    }));

    it('shows the failure and does not refresh when deleting fails', fakeAsync(() => {
      nugetService.deletePackage.and.rejectWith('Package is in use');
      component.deletePackage(pkg);

      dangerModalService.call();
      flushMicrotasks();

      expect(toastService.show).toHaveBeenCalledOnceWith('Package is in use', 'error');
      expect(nugetService.fetchRepositoryPackages).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    }));
  });

  describe('helpers', () => {
    it('packageRoute links to the package inside the active repository', fakeAsync(() => {
      selectRepo();

      expect(component.packageRoute(item('Acme.Lib'))).toBe(`/${REPO}/Acme.Lib`);
    }));

    it('timeAgo renders a relative time', () => {
      expect(component.timeAgo(moment().subtract(3, 'days').toDate())).toBe('3 days ago');
    });
  });

  describe('ngOnDestroy', () => {
    it('stops following repository changes', fakeAsync(() => {
      component.ngOnDestroy();

      selectRepo();

      expect(nugetService.fetchRepositoryPackages).not.toHaveBeenCalled();
      expect(securityService.getArtifactSecuritySummary).not.toHaveBeenCalled();
    }));
  });
});
