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

import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';

import { RepoPermissionInfo, TagDetail } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/service/breadcrumb-security-link.service';
import { RepoLookupService } from '../../../repo-entry/repo-lookup.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { getRepoDomain } from '../../docker-repo-util';
import { DockerService } from '../../service/docker.service';
import { DockerImagesTagDetailComponent } from './docker-images-tag-detail.component';

const REPO = 'docker-repo';
const TAG = {
  imageName: 'nginx',
  name: 'latest',
  digest: 'sha256:manifest',
  configDigest: 'sha256:config',
} as TagDetail;

describe('DockerImagesTagDetailComponent', () => {
  let component: DockerImagesTagDetailComponent;
  let dockerService: jasmine.SpyObj<DockerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let breadcrumbSecurityLinkService: BreadcrumbSecurityLinkService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;
  let currentRepo: { repoName: string; repoType: string } | null;

  beforeEach(() => {
    // The component logs every repository emission it ignores; keep that out of the test output.
    spyOn(console, 'debug');
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    currentRepo = { repoName: REPO, repoType: 'docker' };
    dockerService = jasmine.createSpyObj<DockerService>(
      'DockerService',
      ['fetchTag', 'fetchManifestText', 'fetchConfigText', 'deleteTag'],
      { repoChanges },
    );
    dockerService.fetchTag.and.returnValue(of(TAG));
    dockerService.fetchManifestText.and.returnValue(of('{"schemaVersion":2}'));
    dockerService.fetchConfigText.and.returnValue(of('{"os":"linux","architecture":"amd64"}'));
    dockerService.deleteTag.and.returnValue(of(undefined));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    router.navigateByUrl.and.resolveTo(true);
    dangerModalService = new DangerModalService();
    breadcrumbSecurityLinkService = new BreadcrumbSecurityLinkService();
    component = new DockerImagesTagDetailComponent(
      dockerService,
      router,
      { snapshot: { paramMap: convertToParamMap({ image: 'nginx', tag: 'latest' }) } } as ActivatedRoute,
      toastService,
      dangerModalService,
      breadcrumbSecurityLinkService,
      {
        get currentRepo() {
          return currentRepo;
        },
      } as unknown as RepoLookupService,
    );
  });

  afterEach(() => component.ngOnDestroy());

  function select(name = REPO): void {
    repoChanges.next(permission(name, { canManage: true }));
  }

  it('announces the repository type to the breadcrumb, and withdraws it when destroyed', () => {
    let announced: string | null = null;
    breadcrumbSecurityLinkService.repoType$.subscribe((type) => (announced = type));
    expect(announced).toBe('DOCKER');

    component.ngOnDestroy();

    expect(announced).toBeNull();
  });

  describe('when a repository is selected', () => {
    it('loads the tag of the route and builds the pull command', () => {
      select();

      expect(dockerService.fetchTag).toHaveBeenCalledOnceWith('nginx', 'latest');
      expect(component.imageName).toBe('nginx');
      expect(component.tagName).toBe('latest');
      expect(component.tagInfo).toBe(TAG);
      expect(component.installText).toBe(`docker pull ${getRepoDomain()}/${REPO}/nginx:latest`);
      expect(component.loading).toBeFalse();
    });

    it('loads the manifest of the tag, and the config pretty-printed', () => {
      select();

      expect(dockerService.fetchManifestText).toHaveBeenCalledOnceWith('nginx', 'sha256:manifest');
      expect(component.manifestText).toBe('{"schemaVersion":2}');
      expect(dockerService.fetchConfigText).toHaveBeenCalledOnceWith('nginx', 'sha256:config');
      expect(component.configText).toBe('{\n  "os": "linux",\n  "architecture": "amd64"\n}');
    });

    it('does not load a config for a tag that has none', () => {
      dockerService.fetchTag.and.returnValue(of({ ...TAG, configDigest: null } as unknown as TagDetail));

      select();

      expect(dockerService.fetchConfigText).not.toHaveBeenCalled();
      expect(component.configText).toBeUndefined();
    });

    it('keeps the manifest and config empty when they cannot be loaded', () => {
      dockerService.fetchManifestText.and.returnValue(throwError(() => new Error('boom')));
      dockerService.fetchConfigText.and.returnValue(throwError(() => new Error('boom')));

      select();

      expect(component.manifestText).toBeUndefined();
      expect(component.configText).toBeUndefined();
      expect(component.tagInfo).toBe(TAG);
    });

    it('stops loading and shows no tag when the request fails', () => {
      dockerService.fetchTag.and.returnValue(throwError(() => new Error('boom')));

      select();

      expect(component.loading).toBeFalse();
      expect(component.tagInfo).toBeUndefined();
      expect(dockerService.fetchManifestText).not.toHaveBeenCalled();
    });

    it('ignores a repository that is not the one the route is in', () => {
      select('another-repo');

      expect(dockerService.fetchTag).not.toHaveBeenCalled();
    });

    it('ignores every repository while the current repository is not known', () => {
      currentRepo = null;

      select();

      expect(dockerService.fetchTag).not.toHaveBeenCalled();
    });

    it('ignores an empty repository value', () => {
      repoChanges.next(null);

      expect(dockerService.fetchTag).not.toHaveBeenCalled();
    });

    it('stops following repository changes when destroyed', () => {
      component.ngOnDestroy();

      select();

      expect(dockerService.fetchTag).not.toHaveBeenCalled();
    });
  });

  describe('deleteTag', () => {
    beforeEach(() => select());

    it('asks for confirmation before deleting anything', () => {
      component.deleteTag();

      expect(dangerModalService.modal).toEqual({ title: 'Delete Version', action: 'Delete', message: null });
      expect(dockerService.deleteTag).not.toHaveBeenCalled();
    });

    it("deletes the tag, then goes to the image's tag list and toasts", async () => {
      component.deleteTag();

      dangerModalService.call();
      await Promise.resolve();

      expect(dockerService.deleteTag).toHaveBeenCalledOnceWith('nginx', 'latest');
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith(`/${REPO}/nginx`);
      expect(toastService.show).toHaveBeenCalledOnceWith('Tag deleted successfully', 'success');
    });

    it('shows the page as loading until the delete answers', () => {
      const answer = new Subject<void>();
      dockerService.deleteTag.and.returnValue(answer);
      component.deleteTag();

      dangerModalService.call();
      expect(component.loading).toBeTrue();

      answer.complete();
      expect(component.loading).toBeFalse();
    });

    it('stays on the page, without a toast, when the delete fails', () => {
      dockerService.deleteTag.and.returnValue(throwError(() => new Error('boom')));
      component.deleteTag();

      dangerModalService.call();

      expect(router.navigateByUrl).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });
  });
});
