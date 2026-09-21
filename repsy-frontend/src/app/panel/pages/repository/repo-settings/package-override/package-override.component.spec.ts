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
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { generalParentForm, lastSentForm, releaseAwareParentForm } from '../testing/repo-settings-spec-helpers';
import { PackageOverrideComponent } from './package-override.component';

const REPO = 'acme-repo';

describe('PackageOverrideComponent', () => {
  let component: PackageOverrideComponent;
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let fetchCount: number;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['updateSettings']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    repoApi.updateSettings.and.returnValue(of({}) as never);

    component = new PackageOverrideComponent(repoApi, toastService);
    component.repoName = REPO;
    fetchCount = 0;
    component.fetch.subscribe(() => fetchCount++);
  });

  it('starts from the override setting of the parent form', () => {
    component.parentForm = generalParentForm({ allowOverride: false });
    component.ngOnInit();

    expect(component.allowOverride).toBeFalse();
  });

  it('blocks overriding on a general repository and saves only the general settings', () => {
    component.repoType = RepoType.DOCKER;
    component.parentForm = generalParentForm({
      privateRepository: true,
      allowOverride: true,
      securityScanEnabled: false,
    });
    component.ngOnInit();
    component.allowOverride = false;

    component.changeOverride();

    expect(repoApi.updateSettings.calls.mostRecent().args[0]).toBe(REPO);
    expect(lastSentForm(repoApi.updateSettings, 1)).toEqual({
      privateRepo: true,
      allowOverride: false,
      releases: false,
      snapshots: false,
      securityScanEnabled: false,
    });
    expect(component.parentForm.get('allowOverride').value).toBeFalse();
    expect(toastService.show).toHaveBeenCalledOnceWith('Package override is now blocked', 'success');
    expect(fetchCount).toBe(1);
  });

  it('allows overriding again', () => {
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({ allowOverride: false });
    component.ngOnInit();
    component.allowOverride = true;

    component.changeOverride();

    expect(lastSentForm(repoApi.updateSettings, 1)).toEqual(jasmine.objectContaining({ allowOverride: true }));
    expect(component.parentForm.get('allowOverride').value).toBeTrue();
    expect(toastService.show).toHaveBeenCalledOnceWith('Package override is now allowed', 'success');
  });

  [RepoType.MAVEN, RepoType.NUGET].forEach((repoType) => {
    it(`sends the release and snapshot flags of a ${repoType} repository along`, () => {
      component.repoType = repoType;
      component.parentForm = releaseAwareParentForm({
        privateRepository: true,
        releases: true,
        snapshots: false,
        allowOverride: true,
        securityScanEnabled: false,
      });
      component.ngOnInit();
      component.allowOverride = false;

      component.changeOverride();

      expect(lastSentForm(repoApi.updateSettings, 1)).toEqual({
        privateRepo: true,
        allowOverride: false,
        releases: true,
        snapshots: false,
        securityScanEnabled: false,
      });
    });
  });

  it('keeps the parent form and stays quiet when saving fails', () => {
    repoApi.updateSettings.and.returnValue(throwError(() => new Error('boom')));
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({ allowOverride: true });
    component.ngOnInit();
    component.allowOverride = false;

    component.changeOverride();

    expect(component.parentForm.get('allowOverride').value).toBeTrue();
    expect(fetchCount).toBe(0);
    expect(toastService.show).not.toHaveBeenCalled();
  });
});
