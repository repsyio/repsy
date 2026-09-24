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
///

import { of, throwError } from 'rxjs';

import { ProtocolRepoControllerService } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoSupport, RepoType } from '../../../../shared/dto/repo/repo-type';
import { MavenService } from '../../maven/service/maven.service';
import { lastSentForm, releaseAwareParentForm } from '../testing/repo-settings-spec-helpers';
import { VersionAllowanceComponent } from './maven-version-allowence.component';

const REPO = 'acme-repo';

describe('VersionAllowanceComponent', () => {
  let component: VersionAllowanceComponent;
  let mavenService: jasmine.SpyObj<MavenService>;
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let fetchCount: number;

  beforeEach(() => {
    mavenService = jasmine.createSpyObj<MavenService>('MavenService', ['updateRepoSettings']);
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'updateRepoSettings',
    ]);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    mavenService.updateRepoSettings.and.returnValue(of(undefined));
    repoApi.updateRepoSettings.and.returnValue(of({}) as never);

    component = new VersionAllowanceComponent(mavenService, repoApi, toastService);
    component.repoName = REPO;
    fetchCount = 0;
    component.fetch.subscribe(() => fetchCount++);
  });

  function init(repoType: RepoType, snapshots: boolean, releases: boolean): void {
    component.repoType = repoType;
    component.parentForm = releaseAwareParentForm({
      privateRepository: true,
      allowOverride: false,
      securityScanEnabled: false,
      snapshots,
      releases,
    });
    component.ngOnInit();
  }

  describe('ngOnInit', () => {
    it('offers all, snapshots and releases for a Maven repository', () => {
      init(RepoType.MAVEN, true, true);

      expect(component.repoOptions).toEqual([RepoSupport.ALL, RepoSupport.SNAPSHOTS, RepoSupport.RELEASES]);
    });

    it('offers all, pre-release and stable for a NuGet repository', () => {
      init(RepoType.NUGET, true, true);

      expect(component.repoOptions).toEqual([RepoSupport.ALL, RepoSupport.PRE_RELEASE, RepoSupport.STABLE]);
    });

    it('preselects the option that matches the settings of a Maven repository', () => {
      init(RepoType.MAVEN, true, false);
      expect(component.selectedOption).toBe(RepoSupport.SNAPSHOTS);

      init(RepoType.MAVEN, false, true);
      expect(component.selectedOption).toBe(RepoSupport.RELEASES);

      init(RepoType.MAVEN, true, true);
      expect(component.selectedOption).toBe(RepoSupport.ALL);

      init(RepoType.MAVEN, false, false);
      expect(component.selectedOption).toBe(RepoSupport.ALL);
    });

    it('preselects the option that matches the settings of a NuGet repository', () => {
      init(RepoType.NUGET, true, false);
      expect(component.selectedOption).toBe(RepoSupport.PRE_RELEASE);

      init(RepoType.NUGET, false, true);
      expect(component.selectedOption).toBe(RepoSupport.STABLE);

      init(RepoType.NUGET, true, true);
      expect(component.selectedOption).toBe(RepoSupport.ALL);
    });
  });

  describe('selectType on a Maven repository', () => {
    beforeEach(() => init(RepoType.MAVEN, true, true));

    const cases: [RepoSupport, boolean, boolean][] = [
      [RepoSupport.ALL, true, true],
      [RepoSupport.SNAPSHOTS, true, false],
      [RepoSupport.RELEASES, false, true],
    ];

    cases.forEach(([option, snapshots, releases]) => {
      it(`saves snapshots=${snapshots} and releases=${releases} for "${option}", keeping the other settings`, () => {
        component.selectType(option);

        expect(repoApi.updateRepoSettings).not.toHaveBeenCalled();
        expect(mavenService.updateRepoSettings).toHaveBeenCalledTimes(1);
        expect(lastSentForm(mavenService.updateRepoSettings, 0)).toEqual({
          privateRepo: true,
          allowOverride: false,
          snapshots,
          releases,
          securityScanEnabled: false,
        });
      });
    });

    it('refreshes the settings and toasts once saved', () => {
      component.selectType(RepoSupport.RELEASES);

      expect(fetchCount).toBe(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Version allowance has changed to releases', 'success');
    });

    it('does nothing further when saving fails, leaving the error to the interceptor', () => {
      mavenService.updateRepoSettings.and.returnValue(throwError(() => new Error('boom')));

      component.selectType(RepoSupport.RELEASES);

      expect(fetchCount).toBe(0);
      expect(toastService.show).not.toHaveBeenCalled();
    });
  });

  describe('selectType on a NuGet repository', () => {
    beforeEach(() => init(RepoType.NUGET, true, true));

    const cases: [RepoSupport, boolean, boolean][] = [
      [RepoSupport.ALL, true, true],
      [RepoSupport.PRE_RELEASE, true, false],
      [RepoSupport.STABLE, false, true],
    ];

    cases.forEach(([option, snapshots, releases]) => {
      it(`saves snapshots=${snapshots} and releases=${releases} for "${option}" through the repository API`, () => {
        component.selectType(option);

        expect(mavenService.updateRepoSettings).not.toHaveBeenCalled();
        expect(repoApi.updateRepoSettings).toHaveBeenCalledTimes(1);
        expect(repoApi.updateRepoSettings.calls.mostRecent().args[0]).toBe(REPO);
        expect(lastSentForm(repoApi.updateRepoSettings, 1)).toEqual({
          privateRepo: true,
          allowOverride: false,
          snapshots,
          releases,
          securityScanEnabled: false,
        });
      });
    });

    it('refreshes the settings and toasts once saved', () => {
      component.selectType(RepoSupport.STABLE);

      expect(fetchCount).toBe(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Version allowance has changed to stable', 'success');
    });

    it('does nothing further when saving fails', () => {
      repoApi.updateRepoSettings.and.returnValue(throwError(() => new Error('boom')));

      component.selectType(RepoSupport.STABLE);

      expect(fetchCount).toBe(0);
      expect(toastService.show).not.toHaveBeenCalled();
    });
  });
});
