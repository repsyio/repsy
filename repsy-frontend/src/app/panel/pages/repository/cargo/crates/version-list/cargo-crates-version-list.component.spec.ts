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

import { CrateVersionListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { SecurityService } from '../../../../security/service/security.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import {
  describeLastVersionDelete,
  describeRepoListBehavior,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { CrateInfo } from '../../dto/crate-info';
import { CargoService } from '../../service/cargo.service';
import { CargoCratesVersionListComponent } from './cargo-crates-version-list.component';

const CRATE = { name: 'serde' } as CrateInfo;
const VERSION = { version: '1.0.0' } as CrateVersionListItem;

describe('CargoCratesVersionListComponent', () => {
  let component: CargoCratesVersionListComponent;
  let route: ActivatedRoute;
  let cargoService: jasmine.SpyObj<CargoService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    cargoService = jasmine.createSpyObj<CargoService>(
      'CargoService',
      ['fetchCrate', 'fetchCrateVersions', 'deleteCrate', 'deleteCrateVersion'],
      { repoChanges },
    );
    cargoService.fetchCrate.and.returnValue(of(CRATE));
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    dangerModalService = new DangerModalService();
    route = { snapshot: { paramMap: convertToParamMap({ crate: 'serde' }) } } as ActivatedRoute;
    component = new CargoCratesVersionListComponent(
      route,
      { username: 'alice' } as AuthService,
      cargoService,
      toastService,
      dangerModalService,
      router,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: cargoService.fetchCrateVersions,
      args: { search: 1, sort: 2, page: 3 },
      respond: (content, totalPages) =>
        cargoService.fetchCrateVersions.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => cargoService.fetchCrateVersions.and.returnValue(throwError(() => 'boom')),
      security: { watch: securityService.watchVersionSecuritySummary, argsFor: (repoName) => [repoName, 'serde'] },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, {
      security: true,
      sortResetsPage: true,
      search: { typed: '1.0', loaded: '1.0' },
    }));

  describe('the listing', () => {
    it('loads the crate of the route, then its versions', fakeAsync(() => {
      build().respond([VERSION], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.packageName).toBe('serde');
      expect(cargoService.fetchCrate).toHaveBeenCalledOnceWith('serde');
      expect(cargoService.fetchCrateVersions).toHaveBeenCalledOnceWith('serde', '', component.sortOption, 0, 10);
      expect(component.crate).toBe(CRATE);
      expect(component.versions).toEqual([VERSION]);
    }));

    it('does not load versions when the crate cannot be loaded', fakeAsync(() => {
      build();
      cargoService.fetchCrate.and.returnValue(throwError(() => 'gone'));

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(cargoService.fetchCrateVersions).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
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
      removeVersion: cargoService.deleteCrateVersion,
      removeVersionArgs: ['serde', '1.0.0'],
      removePackage: cargoService.deleteCrate,
      removePackageArgs: ['serde'],
      navigate: router.navigate,
      navigateArgs: [['..'], { relativeTo: route }],
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
