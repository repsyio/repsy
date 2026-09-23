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

import moment from 'moment';
import { BehaviorSubject, of, throwError } from 'rxjs';

import { ArtifactListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import {
  describeRepoListBehavior,
  describeSimpleDelete,
  ListFixture,
  pageOf,
} from '../../../testing/repo-list-spec-helpers';
import { MavenService } from '../../service/maven.service';
import { MavenArtifactsGroupListComponent } from './maven-artifacts-group-list.component';

const ITEM_UNDER_TEST = { groupName: 'org.acme', artifactName: 'lib' } as ArtifactListItem;

describe('MavenArtifactsGroupListComponent', () => {
  let component: MavenArtifactsGroupListComponent;
  let service: jasmine.SpyObj<MavenService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    service = jasmine.createSpyObj<MavenService>('MavenService', ['searchGroups', 'deleteGroup'], { repoChanges });
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new MavenArtifactsGroupListComponent(
      { username: 'alice' } as AuthService,
      service,
      dangerModalService,
      toastService,
    );
    return {
      component,
      repoChanges,
      load: service.searchGroups,
      args: { search: 0, sort: 1, page: 2 },
      respond: (content, totalPages) => service.searchGroups.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => service.searchGroups.and.returnValue(throwError(() => 'boom')),
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, {
      security: false,
      search: { typed: 'acme', loaded: 'acme' },
    }));

  describe('deleteGroup', () => {
    describeSimpleDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: service.deleteGroup,
      invoke: () => component.deleteGroup(ITEM_UNDER_TEST),
      title: 'Delete Group',
      message: 'Group deleted successfully',
      removeArgs: ['org.acme'],
    }));
  });

  describe('helpers', () => {
    beforeEach(() => build());

    it('exposes the signed-in username', () => {
      expect(component.username).toBe('alice');
    });

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
