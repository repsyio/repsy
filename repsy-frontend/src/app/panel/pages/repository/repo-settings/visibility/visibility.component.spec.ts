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
import { VisibilityComponent } from './visibility.component';

const REPO = 'acme-repo';

describe('VisibilityComponent', () => {
  let component: VisibilityComponent;
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let fetchCount: number;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['updateSettings']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    repoApi.updateSettings.and.returnValue(of({}) as never);

    component = new VisibilityComponent(repoApi, toastService);
    component.repoName = REPO;
    fetchCount = 0;
    component.fetch.subscribe(() => fetchCount++);
  });

  it('makes a general repository private and saves only the general settings', () => {
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({
      privateRepository: false,
      allowOverride: false,
      securityScanEnabled: false,
    });

    component.changePrivacy(false);

    expect(component.parentForm.get('privateRepository').value).toBeTrue();
    expect(repoApi.updateSettings.calls.mostRecent().args[0]).toBe(REPO);
    expect(lastSentForm(repoApi.updateSettings, 1)).toEqual({
      privateRepo: true,
      allowOverride: false,
      releases: false,
      snapshots: false,
      securityScanEnabled: false,
    });
    expect(toastService.show).toHaveBeenCalledOnceWith('Repository visibility has changed as private', 'success');
    expect(fetchCount).toBe(1);
  });

  it('makes a repository public', () => {
    component.repoType = RepoType.PYPI;
    component.parentForm = generalParentForm({ privateRepository: true });

    component.changePrivacy(true);

    expect(component.parentForm.get('privateRepository').value).toBeFalse();
    expect(lastSentForm(repoApi.updateSettings, 1)).toEqual(jasmine.objectContaining({ privateRepo: false }));
    expect(toastService.show).toHaveBeenCalledOnceWith('Repository visibility has changed as public', 'success');
  });

  it('sends the release and snapshot flags of a NuGet repository along', () => {
    component.repoType = RepoType.NUGET;
    component.parentForm = releaseAwareParentForm({ releases: true, snapshots: false, allowOverride: false });

    component.changePrivacy(false);

    expect(lastSentForm(repoApi.updateSettings, 1)).toEqual({
      privateRepo: true,
      allowOverride: false,
      releases: true,
      snapshots: false,
      securityScanEnabled: true,
    });
  });

  it('sends the release and snapshot flags of a Maven repository along', () => {
    component.repoType = RepoType.MAVEN;
    component.parentForm = releaseAwareParentForm({
      releases: false,
      snapshots: true,
      allowOverride: false,
      securityScanEnabled: false,
    });

    component.changePrivacy(true);

    expect(lastSentForm(repoApi.updateSettings, 1)).toEqual(
      jasmine.objectContaining({
        privateRepo: false,
        allowOverride: false,
        releases: false,
        snapshots: true,
        securityScanEnabled: false,
      }),
    );
  });

  it('neither refreshes nor toasts when saving fails, leaving the error to the interceptor', () => {
    repoApi.updateSettings.and.returnValue(throwError(() => new Error('boom')));
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm();

    component.changePrivacy(false);

    expect(fetchCount).toBe(0);
    expect(toastService.show).not.toHaveBeenCalled();
  });
});
