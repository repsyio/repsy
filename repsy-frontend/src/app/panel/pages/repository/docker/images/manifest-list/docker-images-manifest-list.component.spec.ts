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
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import moment from 'moment';
import { BehaviorSubject, of, throwError } from 'rxjs';

import { RepoPermissionInfo } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { describeRepoListBehavior, ListFixture, pageOf, REPO_NAME } from '../../../testing/repo-list-spec-helpers';
import { getRepoDomain } from '../../docker-repo-util';
import { DockerService } from '../../service/docker.service';
import { DockerImagesManifestListComponent } from './docker-images-manifest-list.component';

describe('DockerImagesManifestListComponent', () => {
  let component: DockerImagesManifestListComponent;
  let dockerService: jasmine.SpyObj<DockerService>;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    dockerService = jasmine.createSpyObj<DockerService>('DockerService', ['searchManifests'], { repoChanges });
    component = new DockerImagesManifestListComponent(
      { snapshot: { paramMap: convertToParamMap({ image: 'nginx', tag: 'latest' }) } } as ActivatedRoute,
      dockerService,
      { username: 'alice' } as AuthService,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
    );
    return {
      component,
      repoChanges,
      load: dockerService.searchManifests,
      args: { search: 0, sort: 1, page: 4 },
      respond: (content, totalPages) =>
        dockerService.searchManifests.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => dockerService.searchManifests.and.returnValue(throwError(() => 'boom')),
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, { security: false, search: { typed: 'sha256', loaded: 'sha256' } }));

  describe('the listing', () => {
    it('loads the manifests of the image tag in the route and builds the pull command', fakeAsync(() => {
      build().respond([], 0);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.imageName).toBe('nginx');
      expect(component.tagName).toBe('latest');
      expect(dockerService.searchManifests).toHaveBeenCalledOnceWith(
        '',
        component.sortOption,
        'nginx',
        'latest',
        0,
        10,
      );
      expect(component.installText).toBe(`docker pull ${getRepoDomain()}/${REPO_NAME}/nginx:latest`);
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
