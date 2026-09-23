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

import { ArtifactListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
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
import { MavenArtifactsListComponent } from './maven-artifacts-list.component';

const ARTIFACT = { groupName: 'org.acme', artifactName: 'lib' } as ArtifactListItem;

describe('MavenArtifactsListComponent', () => {
  let component: MavenArtifactsListComponent;
  let mavenService: jasmine.SpyObj<MavenService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(): ListFixture {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    mavenService = jasmine.createSpyObj<MavenService>('MavenService', ['searchArtifacts', 'deleteArtifact'], {
      repoChanges,
    });
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchArtifactSecuritySummary']);
    securityService.watchArtifactSecuritySummary.and.returnValue(of({}));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    dangerModalService = new DangerModalService();
    component = new MavenArtifactsListComponent(
      { username: 'alice' } as AuthService,
      mavenService,
      { snapshot: { paramMap: convertToParamMap({ group: 'org.acme' }) } } as ActivatedRoute,
      toastService,
      dangerModalService,
      router,
      securityService,
    );
    return {
      component,
      repoChanges,
      load: mavenService.searchArtifacts,
      args: { search: 1, sort: 2, page: 3 },
      respond: (content, totalPages) =>
        mavenService.searchArtifacts.and.returnValue(of(pageOf(content, totalPages) as never)),
      fail: () => mavenService.searchArtifacts.and.returnValue(throwError(() => 'boom')),
      security: { watch: securityService.watchArtifactSecuritySummary, argsFor: (repoName) => [repoName] },
    };
  }

  describe('shared list behavior', () =>
    describeRepoListBehavior(build, { security: true, search: { typed: 'lib', loaded: 'lib' } }));

  describe('the listing', () => {
    it('is scoped to the group from the route', fakeAsync(() => {
      build().respond([], 0);

      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(mavenService.searchArtifacts).toHaveBeenCalledOnceWith('org.acme', '', component.sortOption, 0, 10);
    }));
  });

  describe('deleteArtifact', () => {
    describeEmptyingDelete(() => ({
      list: build(),
      dangerModal: dangerModalService,
      toast: toastService.show,
      remove: mavenService.deleteArtifact,
      invoke: () => component.deleteArtifact(ARTIFACT),
      title: 'Delete Artifact',
      message: 'Artifact deleted successfully',
      removeArgs: ['org.acme', 'lib'],
      navigate: router.navigateByUrl,
      navigateArgs: [`/${REPO_NAME}`],
    }));
  });

  describe('helpers', () => {
    beforeEach(() => build());

    it('exposes the signed-in username', () => {
      expect(component.username).toBe('alice');
    });

    it('packageRoute links to the artifact inside its group', fakeAsync(() => {
      mavenService.searchArtifacts.and.returnValue(of(pageOf([], 0) as never));
      repoChanges.next(permission(REPO_NAME));
      flushMicrotasks();

      expect(component.packageRoute(ARTIFACT)).toBe(`/${REPO_NAME}/org.acme/lib`);
    }));

    it('packageSecurityKey joins group and artifact', () => {
      expect(component.packageSecurityKey(ARTIFACT)).toBe('org.acme:lib');
    });

    it('imageContain recognizes the packaging types that have an icon', () => {
      for (const known of ['jar', 'war', 'maven-plugin', 'pom', 'aar']) {
        expect(component.imageContain(known)).withContext(known).toBeTrue();
      }
      expect(component.imageContain('zip')).toBeFalse();
    });

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
