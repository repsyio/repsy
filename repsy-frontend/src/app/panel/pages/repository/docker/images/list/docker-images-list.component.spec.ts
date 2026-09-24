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

import { ImageListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { DockerService } from '../../service/docker.service';
import { DockerImagesListComponent } from './docker-images-list.component';

const ITEM_UNDER_TEST = { name: 'nginx' } as ImageListItem;

describe('DockerImagesListComponent', () => {
  let component: DockerImagesListComponent;
  let service: jasmine.SpyObj<DockerService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    service = jasmine.createSpyObj<DockerService>('DockerService', ['searchImages', 'deleteImage'], { repoChanges });
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchArtifactSecuritySummary']);
    securityService.watchArtifactSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new DockerImagesListComponent(
      service,
      { username: 'alice' } as AuthService,
      toastService,
      dangerModalService,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: service.searchImages,
      args: { search: 0, sort: 1, page: 2 },
      respond: (content, totalPages) => service.searchImages.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => service.searchImages.and.returnValue(throwError(() => 'boom')),
      security: { watch: securityService.watchArtifactSecuritySummary, argsFor: (repoName) => [repoName] },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, {
      security: true,
      search: { typed: 'acme', loaded: 'acme' },
    }));

  describe('deleteImage', () => {
    describeSimpleDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: service.deleteImage,
      invoke: () => component.deleteImage(ITEM_UNDER_TEST),
      title: 'Delete Image',
      message: 'Image deleted successfully',
      removeArgs: ['nginx'],
    }));
  });

  describe('helpers', () => {
    beforeEach(() => build());

    it('exposes the signed-in username', () => {
      expect(component.username).toBe('alice');
    });

    it('packageRoute links to the item inside the active repository', fakeAsync(() => {
      service.searchImages.and.returnValue(of(pageOf([], 0) as never));
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.packageRoute(ITEM_UNDER_TEST)).toBe(`/${REPO_NAME}/nginx`);
    }));

    it('says an image has no tags only when the server counts none', () => {
      expect(component.hasNoTags({ name: 'a', tagCount: 0 })).toBeTrue();
      expect(component.hasNoTags({ name: 'a', tagCount: 2 })).toBeFalse();
      expect(component.hasNoTags({ name: 'a' })).toBeFalse();
    });

    it('describes the untagged manifests of an image without tags', () => {
      expect(component.untaggedLabel({ name: 'a', untaggedManifestCount: 1 })).toBe('1 untagged manifest');
      expect(component.untaggedLabel({ name: 'a', untaggedManifestCount: 3 })).toBe('3 untagged manifests');
      expect(component.untaggedLabel({ name: 'a', untaggedManifestCount: 0 })).toBe('no manifests');
      expect(component.untaggedLabel({ name: 'a' })).toBe('no manifests');
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
