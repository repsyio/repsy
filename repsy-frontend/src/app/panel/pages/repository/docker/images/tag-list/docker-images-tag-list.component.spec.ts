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
import {
  describeEmptyingDelete,
  describeRepoListBehavior,
  ListFixture,
  pageOf,
  REPO_NAME,
} from '../../../testing/repo-list-spec-helpers';
import { getRepoDomain } from '../../docker-repo-util';
import { TagListItem } from '../../dto/tag-list-item';
import { DockerService } from '../../service/docker.service';
import { DockerImagesTagListComponent } from './docker-images-tag-list.component';

describe('DockerImagesTagListComponent', () => {
  let component: DockerImagesTagListComponent;
  let dockerService: jasmine.SpyObj<DockerService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;
  const TAG = { name: 'latest' } as TagListItem;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    dockerService = jasmine.createSpyObj<DockerService>('DockerService', ['searchTags', 'deleteTag'], {
      repoChanges,
    });
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    dangerModalService = new DangerModalService();
    component = new DockerImagesTagListComponent(
      { snapshot: { paramMap: convertToParamMap({ image: 'nginx' }) } } as ActivatedRoute,
      dockerService,
      { username: 'alice' } as AuthService,
      toastService,
      dangerModalService,
      router,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: dockerService.searchTags,
      args: { search: 0, sort: 1, page: 3 },
      respond: (content, totalPages) =>
        dockerService.searchTags.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => dockerService.searchTags.and.returnValue(throwError(() => 'boom')),
      security: {
        watch: securityService.watchVersionSecuritySummary,
        argsFor: (repoName) => [repoName, 'nginx'],
      },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, { security: true, search: { typed: 'lat', loaded: 'lat' } }));

  describe('the listing', () => {
    it('loads the tags of the image in the route and builds the pull command', fakeAsync(() => {
      build().respond([TAG], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.imageName).toBe('nginx');
      expect(dockerService.searchTags).toHaveBeenCalledOnceWith('', component.sortOption, 'nginx', 0, 10);
      expect(component.installText).toBe(`docker pull ${getRepoDomain()}/${REPO_NAME}/nginx`);
    }));
  });

  describe('deleteTag', () => {
    describeEmptyingDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: dockerService.deleteTag,
      invoke: () => component.deleteTag(TAG),
      title: 'Delete Tag',
      message: 'Tag deleted successfully',
      removeArgs: ['nginx', 'latest'],
      navigate: router.navigate,
      navigateArgs: [[`/${REPO_NAME}`]],
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
