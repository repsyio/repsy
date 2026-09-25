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
import { MavenService } from '../../service/maven.service';
import { MavenArtifactsVersionListComponent } from './maven-artifacts-version-list.component';

describe('MavenArtifactsVersionListComponent', () => {
  let component: MavenArtifactsVersionListComponent;
  let mavenService: jasmine.SpyObj<MavenService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;
  const VERSION = { versionName: '1.2.3' } as Parameters<MavenArtifactsVersionListComponent['deleteVersion']>[0];

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    mavenService = jasmine.createSpyObj<MavenService>(
      'MavenService',
      ['searchArtifactVersions', 'deleteVersion', 'getVersionDeleteWarning'],
      { repoChanges },
    );
    mavenService.getVersionDeleteWarning.and.returnValue(of(null));
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchVersionSecuritySummary']);
    securityService.watchVersionSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    dangerModalService = new DangerModalService();
    component = new MavenArtifactsVersionListComponent(
      { username: 'alice' } as AuthService,
      mavenService,
      {
        snapshot: { paramMap: convertToParamMap({ group: 'org.acme', artifact: 'lib' }) },
      } as ActivatedRoute,
      router,
      toastService,
      dangerModalService,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: mavenService.searchArtifactVersions,
      args: { search: 2, sort: 3, page: 4 },
      respond: (content, totalPages) =>
        mavenService.searchArtifactVersions.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => mavenService.searchArtifactVersions.and.returnValue(throwError(() => 'boom')),
      security: {
        watch: securityService.watchVersionSecuritySummary,
        argsFor: (repoName) => [repoName, 'org.acme:lib'],
      },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, { security: true, search: { typed: '1.2', loaded: '1.2' } }));

  describe('the listing', () => {
    it('loads the versions of the group and artifact in the route', fakeAsync(() => {
      build().respond([VERSION], 1);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(mavenService.searchArtifactVersions).toHaveBeenCalledOnceWith(
        'org.acme',
        'lib',
        '',
        component.sortOption,
        0,
        10,
      );
    }));
  });

  describe('deleteVersion', () => {
    describeEmptyingDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: mavenService.deleteVersion,
      invoke: () => component.deleteVersion(VERSION),
      title: 'Delete Version',
      message: 'Version deleted successfully',
      removeArgs: ['org.acme', 'lib', '1.2.3'],
      navigate: router.navigateByUrl,
      navigateArgs: [`/${REPO_NAME}`],
    }));
  });

  // RPS-1348: the last version of a group's only artifact takes the artifact and the group with it.
  describe('deleteVersion of the last version of the only artifact of its group', () => {
    beforeEach(() => build());

    it('names the artifact and the group that go too, and deletes nothing before the confirmation', () => {
      mavenService.getVersionDeleteWarning.and.returnValue(of('the artifact and the group are removed too'));
      component.versions = [VERSION];

      component.deleteVersion(VERSION);

      expect(mavenService.getVersionDeleteWarning).toHaveBeenCalledOnceWith('org.acme', 'lib');
      expect(dangerModalService.modal).toEqual({
        title: 'Delete Version',
        action: 'Delete',
        message: 'the artifact and the group are removed too',
      });
      expect(mavenService.deleteVersion).not.toHaveBeenCalled();
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
