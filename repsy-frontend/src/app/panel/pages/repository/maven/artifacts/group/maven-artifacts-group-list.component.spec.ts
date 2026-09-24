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

import { ArtifactListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { describeRepoListBehavior, ListFixture, pageOf } from '../../../testing/repo-list-spec-helpers';
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
    service = jasmine.createSpyObj<MavenService>('MavenService', ['searchGroups', 'deleteGroup', 'getGroupSummary'], {
      repoChanges,
    });
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
    // A row is one artifact, but the delete removes the whole group: the dialog says so (RPS-1288).
    beforeEach(() => {
      build();
      service.getGroupSummary.and.returnValue(of({ groupName: 'org.acme', artifactCount: 3, versionCount: 12 }));
      service.deleteGroup.and.returnValue(of(undefined as never));
      service.searchGroups.and.returnValue(of(pageOf([], 1) as never));
    });

    it('names the group and says how many artifacts and versions go with it', () => {
      component.deleteGroup(ITEM_UNDER_TEST);

      expect(service.getGroupSummary).toHaveBeenCalledOnceWith('org.acme');
      expect(dangerModalService.modal).toEqual({
        title: 'Delete Group',
        action: 'Delete',
        message:
          'The whole group org.acme will be deleted, not just this artifact: 3 artifacts and 12 versions. This cannot be undone.',
      });
      expect(service.deleteGroup).not.toHaveBeenCalled();
    });

    it('counts one artifact and one version in the singular', () => {
      service.getGroupSummary.and.returnValue(of({ groupName: 'org.acme', artifactCount: 1, versionCount: 1 }));

      component.deleteGroup(ITEM_UNDER_TEST);

      expect(dangerModalService.modal?.message).toContain(': 1 artifact and 1 version. ');
    });

    it('still names the group and what goes when the counts cannot be read', () => {
      service.getGroupSummary.and.returnValue(throwError(() => 'boom'));

      component.deleteGroup(ITEM_UNDER_TEST);

      expect(dangerModalService.modal?.title).toBe('Delete Group');
      expect(dangerModalService.modal?.message).toBe(
        'The whole group org.acme will be deleted, not just this artifact: all of its artifacts and versions. This cannot be undone.',
      );
    });

    it('deletes the group once the modal confirms, then toasts and refetches the listing', fakeAsync(() => {
      component.deleteGroup(ITEM_UNDER_TEST);

      dangerModalService.call();
      flushMicrotasks();

      expect(service.deleteGroup).toHaveBeenCalledOnceWith('org.acme');
      expect(toastService.show).toHaveBeenCalledOnceWith('Group deleted successfully', 'success');
      expect(service.searchGroups).toHaveBeenCalledTimes(1);
      expect(component.loading).toBeFalse();
    }));

    it('neither toasts nor refetches, and stops loading, when the delete fails', fakeAsync(() => {
      service.deleteGroup.and.returnValue(throwError(() => 'boom'));
      component.deleteGroup(ITEM_UNDER_TEST);

      dangerModalService.call();
      flushMicrotasks();

      expect(toastService.show).not.toHaveBeenCalled();
      expect(service.searchGroups).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
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
