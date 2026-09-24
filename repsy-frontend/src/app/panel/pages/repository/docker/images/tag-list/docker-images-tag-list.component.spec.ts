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
import { BehaviorSubject, of, throwError } from 'rxjs';

import { ImageListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { AuthService } from '../../../../../../auth/pages/service/auth.service';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { SecurityService } from '../../../../security/service/security.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { describeRepoListBehavior, ListFixture, pageOf, REPO_NAME } from '../../../testing/repo-list-spec-helpers';
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
    dockerService = jasmine.createSpyObj<DockerService>(
      'DockerService',
      ['searchTags', 'deleteTag', 'deleteImage', 'fetchImageSummary'],
      {
        repoChanges,
      },
    );
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
    function open(tagCount: number, canManage = true): void {
      const fixture = build();
      fixture.respond(
        Array.from({ length: tagCount }, () => TAG),
        1,
      );
      dockerService.deleteTag.and.returnValue(of(undefined));
      dockerService.fetchImageSummary.and.returnValue(of({ name: 'nginx', tagCount: 0 } as ImageListItem));
      router.navigate.and.returnValue(Promise.resolve(true));
      repoChanges.next(permission(REPO_NAME, { canManage }));
      flushMicrotasks();
      dockerService.searchTags.calls.reset();
    }

    afterEach(() => component.ngOnDestroy());

    it('asks for confirmation before deleting anything', fakeAsync(() => {
      open(2);

      component.deleteTag(TAG);

      expect(dangerModalService.modal).toEqual({ title: 'Delete Tag', action: 'Delete', message: null });
      expect(dockerService.deleteTag).not.toHaveBeenCalled();
    }));

    it('refreshes the listing and toasts when other tags remain', fakeAsync(() => {
      open(2);
      component.deleteTag(TAG);

      dangerModalService.call();
      flushMicrotasks();

      expect(dockerService.deleteTag).toHaveBeenCalledOnceWith('nginx', 'latest');
      expect(dockerService.searchTags).toHaveBeenCalledTimes(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Tag deleted successfully', 'success');
      expect(router.navigate).not.toHaveBeenCalled();
    }));

    it('stays on the page after the last tag, because the image and its manifest are still there', fakeAsync(() => {
      open(1);
      component.deleteTag(TAG);
      dockerService.searchTags.and.returnValue(of(pageOf([], 0) as never));

      dangerModalService.call();
      flushMicrotasks();

      expect(router.navigate).not.toHaveBeenCalled();
      expect(dockerService.searchTags).toHaveBeenCalledTimes(1);
      expect(dockerService.fetchImageSummary).toHaveBeenCalledOnceWith('nginx');
      expect(toastService.show).toHaveBeenCalledOnceWith('Tag deleted successfully', 'success');
      expect(component.hasNoTags).toBeTrue();
    }));

    it('neither refreshes nor toasts when the delete fails', fakeAsync(() => {
      open(1);
      dockerService.deleteTag.and.returnValue(throwError(() => 'boom'));
      component.deleteTag(TAG);

      dangerModalService.call();
      flushMicrotasks();

      expect(dockerService.searchTags).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    }));
  });

  describe('an image without tags', () => {
    function openEmpty(summary: Partial<ImageListItem> | 'gone' | 'failing', search = ''): void {
      const fixture = build();
      fixture.respond([], 0);
      router.navigate.and.returnValue(Promise.resolve(true));
      dockerService.deleteImage.and.returnValue(of(undefined));
      dockerService.fetchImageSummary.and.returnValue(
        summary === 'gone'
          ? throwError(() => new HttpErrorResponse({ status: 404 }))
          : summary === 'failing'
            ? throwError(() => new HttpErrorResponse({ status: 500 }))
            : of({ name: 'nginx', ...summary } as ImageListItem),
      );
      component.searchText = search;
      repoChanges.next(permission(REPO_NAME, { canManage: true }));
      flushMicrotasks();
    }

    afterEach(() => component.ngOnDestroy());

    it('loads what the image still stores and says it has no tags', fakeAsync(() => {
      openEmpty({ tagCount: 0, untaggedManifestCount: 3, untaggedSize: 3 * 1024 * 1024 });

      expect(dockerService.fetchImageSummary).toHaveBeenCalledOnceWith('nginx');
      expect(component.hasNoTags).toBeTrue();
      expect(component.untaggedText).toBe('3 untagged manifests are still stored (3 M), and can be pulled by digest.');
      expect(component.loadingSummary).toBeFalse();
    }));

    it('uses the singular for one manifest and says so when nothing is stored', fakeAsync(() => {
      openEmpty({ tagCount: 0, untaggedManifestCount: 1, untaggedSize: 1024 });
      expect(component.untaggedText).toBe('1 untagged manifest is still stored (1 K), and can be pulled by digest.');

      openEmpty({ tagCount: 0, untaggedManifestCount: 0, untaggedSize: 0 });
      expect(component.untaggedText).toBe('It stores no manifests either.');
    }));

    it('leaves for the image list when the image is gone, without a toast of its own', fakeAsync(() => {
      openEmpty('gone');

      expect(router.navigate).toHaveBeenCalledOnceWith([`/${REPO_NAME}`]);
      expect(component.hasNoTags).toBeFalse();
      expect(component.loadingSummary).toBeFalse();
    }));

    it('stays put on any other failure', fakeAsync(() => {
      openEmpty('failing');

      expect(router.navigate).not.toHaveBeenCalled();
      expect(component.hasNoTags).toBeFalse();
      expect(component.loadingSummary).toBeFalse();
    }));

    it('does not ask when the search simply matched no tag', fakeAsync(() => {
      openEmpty({ tagCount: 4 }, 'zzz');

      expect(dockerService.fetchImageSummary).not.toHaveBeenCalled();
      expect(component.hasNoTags).toBeFalse();
    }));

    it('is not "no tags" while the image still has some', fakeAsync(() => {
      openEmpty({ tagCount: 2, untaggedManifestCount: 0 });

      expect(component.hasNoTags).toBeFalse();
    }));

    it('reloads the list and the summary once the untagged manifests are cleaned', fakeAsync(() => {
      openEmpty({ tagCount: 0, untaggedManifestCount: 2, untaggedSize: 2048 });
      dockerService.searchTags.calls.reset();
      dockerService.fetchImageSummary.calls.reset();

      component.onUntaggedCleaned();
      flushMicrotasks();

      expect(dockerService.searchTags).toHaveBeenCalledTimes(1);
      expect(dockerService.fetchImageSummary).toHaveBeenCalledTimes(1);
    }));

    describe('deleteImage', () => {
      it('asks for confirmation before deleting anything', fakeAsync(() => {
        openEmpty({ tagCount: 0 });

        component.deleteImage();

        expect(dangerModalService.modal).toEqual({ title: 'Delete Image', action: 'Delete', message: null });
        expect(dockerService.deleteImage).not.toHaveBeenCalled();
      }));

      it('deletes the image, then goes to the image list and toasts', fakeAsync(() => {
        openEmpty({ tagCount: 0 });
        component.deleteImage();

        dangerModalService.call();
        flushMicrotasks();

        expect(dockerService.deleteImage).toHaveBeenCalledOnceWith('nginx');
        expect(router.navigate).toHaveBeenCalledOnceWith([`/${REPO_NAME}`]);
        expect(toastService.show).toHaveBeenCalledOnceWith('Image deleted successfully', 'success');
      }));

      it('stays, without a toast, when the delete fails', fakeAsync(() => {
        openEmpty({ tagCount: 0 });
        router.navigate.calls.reset();
        dockerService.deleteImage.and.returnValue(throwError(() => 'boom'));
        component.deleteImage();

        dangerModalService.call();
        flushMicrotasks();

        expect(router.navigate).not.toHaveBeenCalled();
        expect(toastService.show).not.toHaveBeenCalled();
        expect(component.loading).toBeFalse();
      }));
    });
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
