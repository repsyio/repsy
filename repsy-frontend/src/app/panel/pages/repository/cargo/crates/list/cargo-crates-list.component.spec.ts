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

import { environment } from '../../../../../../../environments/environment';
import { CrateListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { CargoService } from '../../service/cargo.service';
import { CargoCratesListComponent } from './cargo-crates-list.component';

const CRATE = { name: 'serde' } as CrateListItem;

describe('CargoCratesListComponent', () => {
  let component: CargoCratesListComponent;
  let cargoService: jasmine.SpyObj<CargoService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    cargoService = jasmine.createSpyObj<CargoService>('CargoService', ['searchCrates', 'deleteCrate'], {
      repoChanges,
    });
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchArtifactSecuritySummary']);
    securityService.watchArtifactSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new CargoCratesListComponent(
      { username: 'alice' } as AuthService,
      cargoService,
      toastService,
      dangerModalService,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: cargoService.searchCrates,
      args: { search: 0, sort: 1, page: 2 },
      respond: (content, totalPages) =>
        cargoService.searchCrates.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => cargoService.searchCrates.and.returnValue(throwError(() => 'boom')),
      security: { watch: securityService.watchArtifactSecuritySummary, argsFor: (repoName) => [repoName] },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, { security: true, search: { typed: 'serde', loaded: 'serde' } }));

  describe('deleteCrate', () => {
    describeSimpleDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: cargoService.deleteCrate,
      invoke: () => component.deleteCrate(CRATE),
      title: 'Delete Crate',
      message: 'Crate deleted successfully',
      removeArgs: ['serde'],
    }));
  });

  describe('helpers', () => {
    beforeEach(() => build());

    it('exposes the repository base URL and the signed-in username', () => {
      expect(component.baseUrl).toBe(environment.repoBaseUrl);
      expect(component.username).toBe('alice');
    });

    it('packageRoute links to the crate inside the active repository', fakeAsync(() => {
      cargoService.searchCrates.and.returnValue(of(pageOf([], 0) as never));
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.packageRoute(CRATE)).toBe(`/${REPO_NAME}/serde`);
    }));

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
