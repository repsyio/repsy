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

import { Router } from '@angular/router';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';

import { ProtocolRepoControllerService, RepoSettingsInfo } from '../../../../../generated/api';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { RepoContext, RepoLookupService } from '../repo-entry/repo-lookup.service';
import { permission, restResponse } from '../testing/protocol-service-spec-helpers';
import { RepositorySettingsComponent } from './repository-settings.component';

const REPO = 'acme-repo';

/** A successful `RestResponse*` reply; the generated client's overloads make a typed spy return value unusable. */
const reply = (data: unknown): never => of(restResponse(data)) as never;

function settings(overrides: Partial<RepoSettingsInfo> = {}): RepoSettingsInfo {
  return { privateRepo: true, allowOverride: false, searchable: true, securityScanEnabled: false, ...overrides };
}

describe('RepositorySettingsComponent', () => {
  let component: RepositorySettingsComponent;
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let router: jasmine.SpyObj<Router>;
  let currentRepo$: BehaviorSubject<RepoContext | null>;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'getPermission',
      'getSettings',
    ]);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    currentRepo$ = new BehaviorSubject<RepoContext | null>(null);

    repoApi.getPermission.and.returnValue(reply(permission(REPO, { canManage: true })));
    repoApi.getSettings.and.returnValue(reply(settings()));

    component = new RepositorySettingsComponent(
      repoApi,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
      router,
      { currentRepo$: currentRepo$.asObservable() } as RepoLookupService,
    );
  });

  afterEach(() => component.ngOnDestroy());

  it('starts loading, with the general form public and the scan and override toggles on', () => {
    expect(component.loading).toBeTrue();
    expect(component.generalSettingsForm.getRawValue()).toEqual({
      privateRepository: false,
      allowOverride: true,
      securityScanEnabled: true,
    });
    expect(component.mavenSettingsForm.getRawValue()).toEqual({
      privateRepository: false,
      releases: false,
      snapshots: false,
      allowOverride: true,
      securityScanEnabled: true,
      pgpVerifyAllSignaturesEnabled: false,
      pgpKeyServerLookupEnabled: true,
    });
  });

  it('waits for a repository context before loading anything', () => {
    component.ngOnInit();

    expect(repoApi.getPermission).not.toHaveBeenCalled();
    expect(component.loading).toBeTrue();
  });

  it('sends a user who cannot manage the repository back to the repository list', () => {
    repoApi.getPermission.and.returnValue(reply(permission(REPO, { canManage: false, canWrite: true })));
    component.ngOnInit();

    currentRepo$.next({ repoName: REPO, repoType: 'npm' });

    expect(repoApi.getPermission).toHaveBeenCalledOnceWith(REPO);
    expect(router.navigate).toHaveBeenCalledOnceWith(['/repositories']);
    expect(repoApi.getSettings).not.toHaveBeenCalled();
  });

  it('loads the settings of a manageable repository and remembers its type and permission', () => {
    component.ngOnInit();

    currentRepo$.next({ repoName: REPO, repoType: 'npm' });

    expect(repoApi.getSettings).toHaveBeenCalledOnceWith(REPO);
    expect(component.repoType).toBe('npm');
    expect(component.activeRepository.repoName).toBe(REPO);
    expect(component.loading).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  ['npm', 'pypi', 'docker', 'cargo', 'golang', 'helm', 'ruby'].forEach((repoType) => {
    it(`fills the general form for a ${repoType} repository`, () => {
      const info = settings({ privateRepo: true, allowOverride: false, securityScanEnabled: false });
      repoApi.getSettings.and.returnValue(reply(info));
      component.ngOnInit();

      currentRepo$.next({ repoName: REPO, repoType: repoType as RepoContext['repoType'] });

      expect(component.repositorySettings).toEqual(info);
      expect(component.generalSettingsForm.getRawValue()).toEqual({
        privateRepository: true,
        allowOverride: false,
        securityScanEnabled: false,
      });
      expect(component.mavenSettingsForm.get('privateRepository').value).toBeFalse();
      expect(component.mavenRepositorySettings).toBeUndefined();
    });
  });

  it('fills the Maven form, including releases, snapshots and the PGP settings, for a Maven repository', () => {
    const info = settings({
      releases: true,
      snapshots: false,
      pgpVerifyAllSignaturesEnabled: true,
      pgpKeyServerLookupEnabled: false,
    });
    repoApi.getSettings.and.returnValue(reply(info));
    component.ngOnInit();

    currentRepo$.next({ repoName: REPO, repoType: 'maven' });

    expect(component.mavenRepositorySettings as unknown).toEqual(info);
    expect(component.mavenSettingsForm.getRawValue()).toEqual({
      privateRepository: true,
      releases: true,
      snapshots: false,
      allowOverride: false,
      securityScanEnabled: false,
      pgpVerifyAllSignaturesEnabled: true,
      pgpKeyServerLookupEnabled: false,
    });
    expect(component.repositorySettings).toBeUndefined();
    expect(component.generalSettingsForm.get('privateRepository').value).toBeFalse();
  });

  it('fills the release-aware form, not the general one, for a NuGet repository', () => {
    const info = settings({ releases: false, snapshots: true });
    repoApi.getSettings.and.returnValue(reply(info));
    component.ngOnInit();

    currentRepo$.next({ repoName: REPO, repoType: 'nuget' });

    expect(component.repositorySettings).toEqual(info);
    expect(component.mavenSettingsForm.getRawValue()).toEqual({
      privateRepository: true,
      releases: false,
      snapshots: true,
      allowOverride: false,
      securityScanEnabled: false,
      // NuGet's settings carry no PGP fields, so its form keeps their defaults.
      pgpVerifyAllSignaturesEnabled: false,
      pgpKeyServerLookupEnabled: true,
    });
    expect(component.generalSettingsForm.get('privateRepository').value).toBeFalse();
    expect(component.mavenRepositorySettings).toBeUndefined();
  });

  it('stops loading and keeps the forms untouched when the settings request fails', () => {
    repoApi.getSettings.and.returnValue(throwError(() => new Error('boom')));
    component.ngOnInit();

    currentRepo$.next({ repoName: REPO, repoType: 'npm' });

    expect(component.loading).toBeFalse();
    expect(component.repositorySettings).toBeUndefined();
    expect(component.generalSettingsForm.get('privateRepository').value).toBeFalse();
  });

  it('getRepoSettings reloads the settings of the active repository', () => {
    component.ngOnInit();
    currentRepo$.next({ repoName: REPO, repoType: 'npm' });
    repoApi.getSettings.calls.reset();
    repoApi.getSettings.and.returnValue(reply(settings({ privateRepo: false })));

    component.getRepoSettings();

    expect(repoApi.getSettings).toHaveBeenCalledOnceWith(REPO);
    expect(component.generalSettingsForm.get('privateRepository').value).toBeFalse();
  });

  it('drops a pending permission lookup when the user switches repositories', () => {
    const first = new Subject<ReturnType<typeof restResponse>>();
    repoApi.getPermission.withArgs('first').and.returnValue(first as never);
    repoApi.getPermission.withArgs('second').and.returnValue(reply(permission('second', { canManage: true })));
    component.ngOnInit();

    currentRepo$.next({ repoName: 'first', repoType: 'npm' });
    currentRepo$.next({ repoName: 'second', repoType: 'pypi' });
    first.next(restResponse(permission('first', { canManage: false })));

    expect(router.navigate).not.toHaveBeenCalled();
    expect(component.activeRepository.repoName).toBe('second');
    expect(repoApi.getSettings).toHaveBeenCalledOnceWith('second');
  });

  it('stops reacting to repository changes once destroyed', () => {
    component.ngOnInit();
    component.ngOnDestroy();

    currentRepo$.next({ repoName: REPO, repoType: 'npm' });

    expect(repoApi.getPermission).not.toHaveBeenCalled();
  });

  it('can be destroyed before it was initialised', () => {
    expect(() => component.ngOnDestroy()).not.toThrow();
  });
});
