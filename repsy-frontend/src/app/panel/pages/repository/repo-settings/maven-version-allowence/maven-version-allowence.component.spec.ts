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

import { of, Subject, throwError } from 'rxjs';

import { ReposApi } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoSupport, RepoType } from '../../../../shared/dto/repo/repo-type';
import { lastSentForm, releaseAwareParentForm } from '../testing/repo-settings-spec-helpers';
import { VersionAllowanceComponent } from './maven-version-allowence.component';

const REPO = 'acme-repo';

describe('VersionAllowanceComponent', () => {
  let component: VersionAllowanceComponent;
  let repoApi: jasmine.SpyObj<ReposApi>;
  let toastService: jasmine.SpyObj<ToastService>;
  let fetchCount: number;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ReposApi>('ReposApi', ['updateRepoSettings']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    repoApi.updateRepoSettings.and.returnValue(of({}) as never);

    component = new VersionAllowanceComponent(repoApi, toastService);
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

  [RepoType.MAVEN, RepoType.NUGET].forEach((repoType) => {
    const cases: [RepoSupport, boolean, boolean][] =
      repoType === RepoType.MAVEN
        ? [
            [RepoSupport.ALL, true, true],
            [RepoSupport.SNAPSHOTS, true, false],
            [RepoSupport.RELEASES, false, true],
          ]
        : [
            [RepoSupport.ALL, true, true],
            [RepoSupport.PRE_RELEASE, true, false],
            [RepoSupport.STABLE, false, true],
          ];

    describe(`selectType on a ${repoType} repository`, () => {
      beforeEach(() => init(repoType, true, true));

      cases.forEach(([option, snapshots, releases]) => {
        it(`sends only snapshots=${snapshots} and releases=${releases} for "${option}" (RPS-1619)`, () => {
          component.selectType(option);

          expect(repoApi.updateRepoSettings).toHaveBeenCalledTimes(1);
          expect(repoApi.updateRepoSettings.calls.mostRecent().args[0]).toBe(REPO);
          expect(lastSentForm(repoApi.updateRepoSettings, 1)).toEqual({ snapshots, releases });
        });
      });

      it('refreshes the settings and toasts once saved', () => {
        const option = cases[2][0];

        component.selectType(option);

        expect(fetchCount).toBe(1);
        expect(toastService.show).toHaveBeenCalledOnceWith(`Version allowance has changed to ${option}`, 'success');
      });

      it('locks the selector while the save is on its way and unlocks it afterwards', () => {
        const inFlight = new Subject<object>();
        repoApi.updateRepoSettings.and.returnValue(inFlight as never);

        component.selectType(cases[1][0]);

        expect(component.saving).toBeTrue();

        inFlight.next({});
        inFlight.complete();

        expect(component.saving).toBeFalse();
      });

      it('puts the selector back to the stored option, and neither refreshes nor toasts, when saving fails (RPS-1618)', () => {
        repoApi.updateRepoSettings.and.returnValue(throwError(() => new Error('boom')));
        component.selectedOption = cases[2][0];

        component.selectType(cases[2][0]);

        expect(component.selectedOption).toBe(RepoSupport.ALL);
        expect(component.saving).toBeFalse();
        expect(fetchCount).toBe(0);
        expect(toastService.show).not.toHaveBeenCalled();
      });

      it('goes back to the last option that was saved, not to the one shown when the page opened', () => {
        component.selectType(cases[1][0]);
        repoApi.updateRepoSettings.and.returnValue(throwError(() => new Error('boom')));
        component.selectedOption = cases[2][0];

        component.selectType(cases[2][0]);

        expect(component.selectedOption).toBe(cases[1][0]);
      });
    });
  });
});
