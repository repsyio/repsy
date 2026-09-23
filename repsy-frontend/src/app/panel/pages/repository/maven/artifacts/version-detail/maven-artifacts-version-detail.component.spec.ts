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

import { ArtifactVersionInfo, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/service/breadcrumb-security-link.service';
import { RepoLookupService } from '../../../repo-entry/repo-lookup.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { MavenService } from '../../service/maven.service';
import { MavenArtifactsVersionDetailComponent } from './maven-artifacts-version-detail.component';

const REPO = 'maven-repo';
const VERSION = {
  artifactGroupName: 'org.acme',
  artifactName: 'lib',
  artifactVersionName: '1.2.3',
} as ArtifactVersionInfo;

describe('MavenArtifactsVersionDetailComponent', () => {
  let component: MavenArtifactsVersionDetailComponent;
  let mavenService: jasmine.SpyObj<MavenService>;
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
    currentRepo = { repoName: REPO, repoType: 'maven' };
    mavenService = jasmine.createSpyObj<MavenService>('MavenService', ['fetchArtifactVersion', 'deleteVersion'], {
      repoChanges,
    });
    mavenService.fetchArtifactVersion.and.returnValue(of(VERSION));
    mavenService.deleteVersion.and.returnValue(of(undefined as never));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    router.navigateByUrl.and.resolveTo(true);
    dangerModalService = new DangerModalService();
    breadcrumbSecurityLinkService = new BreadcrumbSecurityLinkService();
    component = new MavenArtifactsVersionDetailComponent(
      mavenService,
      {
        snapshot: { paramMap: convertToParamMap({ group: 'org.acme', artifact: 'lib', version: '1.2.3' }) },
      } as ActivatedRoute,
      router,
      dangerModalService,
      toastService,
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
    expect(announced).toBe('MAVEN');

    component.ngOnDestroy();

    expect(announced).toBeNull();
  });

  describe('when a repository is selected', () => {
    it('loads the version of the route', () => {
      select();

      expect(mavenService.fetchArtifactVersion).toHaveBeenCalledOnceWith('org.acme', 'lib', '1.2.3');
      expect(component.groupName).toBe('org.acme');
      expect(component.artifactName).toBe('lib');
      expect(component.versionName).toBe('1.2.3');
      expect(component.version).toBe(VERSION);
      expect(component.loading).toBeFalse();
      expect(component.securityArtifactName).toBe('org.acme:lib');
    });

    it('builds the dependency snippet of every build tool from the version coordinates', () => {
      select();

      expect(component.mavenDependencyHtml).toBe(
        '<dependency>\n  <groupId>org.acme</groupId>\n  <artifactId>lib</artifactId>\n  <version>1.2.3</version>\n</dependency>',
      );
      expect(component.gradleDependencyHtml).toBe("implementation 'org.acme:lib:1.2.3'");
      expect(component.gradleKotlinDependencyHtml).toBe('implementation("org.acme:lib:1.2.3")');
      expect(component.sbtDependencyHtml).toBe('libraryDependencies += "org.acme" % "lib" % "1.2.3"');
      expect(component.ivyDependencyHtml).toBe('<dependency org="org.acme" name="lib" rev="1.2.3" />');
      expect(component.groovyDependencyHtml).toBe(
        "@Grapes(\n  @Grab(group='org.acme', module='lib', version='1.2.3')\n)",
      );
      expect(component.leiningenDependencyHtml).toBe('[org.acme/lib "1.2.3"]');
      expect(component.buildrDependencyHtml).toBe("'org.acme:lib:jar:1.2.3'");
      expect(component.purlDependencyHtml).toBe('pkg:maven/org.acme/lib@1.2.3');
      expect(component.bazelDependencyHtml).toBe(
        'maven_jar(\n  name = "lib",\n  artifact = "org.acme:lib:1.2.3",\n  sha1 = "calculating...",\n)',
      );
    });

    it('ignores a repository that is not the one the route is in', () => {
      select('another-repo');

      expect(mavenService.fetchArtifactVersion).not.toHaveBeenCalled();
      expect(component.activeRepo.repoName).toBeUndefined();
    });

    it('ignores every repository while the current repository is not known', () => {
      currentRepo = null;

      select();

      expect(mavenService.fetchArtifactVersion).not.toHaveBeenCalled();
    });

    it('ignores an empty repository value', () => {
      repoChanges.next(null);

      expect(mavenService.fetchArtifactVersion).not.toHaveBeenCalled();
    });

    it('stops loading and shows no version when the request fails', () => {
      mavenService.fetchArtifactVersion.and.returnValue(throwError(() => new Error('boom')));

      select();

      expect(component.loading).toBeFalse();
      expect(component.version).toBeUndefined();
      expect(component.mavenDependencyHtml).toBeUndefined();
    });

    it('stops following repository changes when destroyed', () => {
      component.ngOnDestroy();

      select();

      expect(mavenService.fetchArtifactVersion).not.toHaveBeenCalled();
    });
  });

  describe('deleteVersion', () => {
    beforeEach(() => select());

    it('asks for confirmation before deleting anything', () => {
      component.deleteVersion();

      expect(dangerModalService.modal).toEqual({ title: 'Delete Version', action: 'Delete', message: null });
      expect(mavenService.deleteVersion).not.toHaveBeenCalled();
    });

    it('deletes the version, then goes to the repository and toasts', async () => {
      component.deleteVersion();

      dangerModalService.call();
      await Promise.resolve();

      expect(mavenService.deleteVersion).toHaveBeenCalledOnceWith('org.acme', 'lib', '1.2.3');
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith(`/${REPO}`);
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
    });

    it('shows the page as loading until the delete answers', () => {
      const answer = new Subject<never>();
      mavenService.deleteVersion.and.returnValue(answer);
      component.deleteVersion();

      dangerModalService.call();
      expect(component.loading).toBeTrue();

      answer.complete();
      expect(component.loading).toBeFalse();
    });

    it('stays on the page, without a toast, when the delete fails', () => {
      mavenService.deleteVersion.and.returnValue(throwError(() => new Error('boom')));
      component.deleteVersion();

      dangerModalService.call();

      expect(router.navigateByUrl).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });
  });
});
