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

import { TestBed } from '@angular/core/testing';
import { from, of, throwError } from 'rxjs';

import { DeployTokensApi, NugetPackagesApi, ReposApi } from '../../../../../../generated/api';
import {
  CallCase,
  collect,
  describeCalls,
  describePagedCalls,
  describeRepoSelection,
  httpError,
  PAGE_ARGS,
  PAGE_INDEX,
  PAGE_SIZE,
  PagedCase,
  permission,
  REPO,
  restResponse,
  selectRepo,
  SORT,
} from '../../testing/protocol-service-spec-helpers';
import { NuGetService } from './nuget.service';

/** The generated methods are overloaded on `observe`, which `and.returnValue` cannot resolve. */
function asSpy(method: unknown): jasmine.Spy {
  return method as jasmine.Spy;
}

const PACKAGE = 'Acme.Lib';
const VERSION = '1.2.3';
const TOKEN = 'token-1';

describe('NuGetService', () => {
  let repoApi: jasmine.SpyObj<ReposApi>;
  let tokenApi: jasmine.SpyObj<DeployTokensApi>;
  let nugetApi: jasmine.SpyObj<NugetPackagesApi>;
  let service: NuGetService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ReposApi>('ReposApi', [
      'getRepoPermissions',
      'getRepoUsage',
      'getRepoSettings',
      'updateRepoSettings',
      'updateRepo',
      'deleteRepo',
    ]);
    tokenApi = jasmine.createSpyObj<DeployTokensApi>('DeployTokensApi', [
      'listDeployTokens',
      'rotateDeployToken',
      'createDeployToken',
      'revokeDeployToken',
    ]);
    nugetApi = jasmine.createSpyObj<NugetPackagesApi>('NugetPackagesApi', [
      'searchNugetPackages',
      'getNugetPackage',
      'listNugetVersions',
      'getNugetVersion',
      'deleteNugetPackage',
      'deleteNugetVersion',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ReposApi, useValue: repoApi },
        { provide: DeployTokensApi, useValue: tokenApi },
        { provide: NugetPackagesApi, useValue: nugetApi },
      ],
    });
    service = TestBed.inject(NuGetService);
  });

  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getRepoPermissions,
    probe: (s) => from(s.fetchPackage(PACKAGE)),
    probeApi: () => nugetApi.getNugetPackage,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('updateRepositoryName', () => {
    const form = { name: 'renamed-repo' };

    it('sends the rename for the active repository and re-emits it under the new name', async () => {
      await selectRepo(service, repoApi.getRepoPermissions, REPO, { canManage: true });
      const emissions = collect(service.repoChanges);
      asSpy(repoApi.updateRepo).and.returnValue(of(restResponse(undefined)));

      await service.updateRepositoryName(form);

      expect(repoApi.updateRepo).toHaveBeenCalledOnceWith(REPO, form);
      expect(emissions.at(-1)).toEqual(permission('renamed-repo', { canManage: true }));
    });

    it('sends the calls that follow to the new name', async () => {
      await selectRepo(service, repoApi.getRepoPermissions, REPO);
      asSpy(repoApi.updateRepo).and.returnValue(of(restResponse(undefined)));
      await service.updateRepositoryName(form);
      asSpy(nugetApi.getNugetPackage).and.returnValue(of(null));

      await service.fetchPackage(PACKAGE);

      expect(nugetApi.getNugetPackage).toHaveBeenCalledOnceWith(PACKAGE, 'renamed-repo');
    });

    it('keeps the old name and emits nothing when the rename fails', async () => {
      await selectRepo(service, repoApi.getRepoPermissions, REPO);
      const emissions = collect(service.repoChanges);
      const error = httpError(409);
      asSpy(repoApi.updateRepo).and.returnValue(throwError(() => error));

      await expectAsync(service.updateRepositoryName(form)).toBeRejectedWith(error);

      expect(emissions.length).withContext('only the replayed current value').toBe(1);
      expect(emissions[0]?.repoName).toBe(REPO);
    });

    it('sends the rename without throwing when no repository is selected', async () => {
      asSpy(repoApi.updateRepo).and.returnValue(of(restResponse(undefined)));

      await service.updateRepositoryName(form);

      expect(repoApi.updateRepo).toHaveBeenCalledOnceWith('', form);
      expect(collect(service.repoChanges)).toEqual([null]);
    });
  });

  describe('package calls', () => {
    const paged: PagedCase<NuGetService>[] = [
      {
        name: 'fetchRepositoryPackages',
        invoke: (s, query) => from(s.fetchRepositoryPackages(query, SORT, PAGE_INDEX, PAGE_SIZE)),
        api: () => nugetApi.searchNugetPackages,
        args: (query) => ['', query, ...PAGE_ARGS],
        bare: true,
      },
      {
        name: 'fetchPackageVersions',
        invoke: (s, query) => from(s.fetchPackageVersions(PACKAGE, query, SORT, PAGE_INDEX, PAGE_SIZE)),
        api: () => nugetApi.listNugetVersions,
        args: (query) => [PACKAGE, '', query, ...PAGE_ARGS],
        bare: true,
      },
    ];
    describePagedCalls(() => service, paged);

    const info = { packageId: PACKAGE };
    const versionInfo = { packageId: PACKAGE, version: VERSION };
    const calls: CallCase<NuGetService>[] = [
      {
        name: 'fetchPackage',
        invoke: (s) => from(s.fetchPackage(PACKAGE)),
        api: () => nugetApi.getNugetPackage,
        args: [PACKAGE, ''],
        response: info,
        expected: info,
      },
      {
        name: 'fetchPackageVersion',
        invoke: (s) => from(s.fetchPackageVersion(PACKAGE, VERSION)),
        api: () => nugetApi.getNugetVersion,
        args: [PACKAGE, VERSION, ''],
        response: versionInfo,
        expected: versionInfo,
      },
      {
        name: 'deletePackage',
        invoke: (s) => from(s.deletePackage(PACKAGE)),
        api: () => nugetApi.deleteNugetPackage,
        args: [PACKAGE, ''],
        response: undefined,
        expected: undefined,
        notCalled: () => [nugetApi.deleteNugetVersion],
      },
      {
        name: 'deletePackageVersion',
        invoke: (s) => from(s.deletePackageVersion(PACKAGE, VERSION)),
        api: () => nugetApi.deleteNugetVersion,
        args: [PACKAGE, VERSION, ''],
        response: undefined,
        expected: undefined,
        notCalled: () => [nugetApi.deleteNugetPackage],
      },
    ];
    describeCalls(() => service, calls);
  });

  describe('repository calls', () => {
    const settingsForm = { description: 'settings' } as never;
    const descriptionForm = { description: 'new description' };
    const usage = { usedStorage: 1 };
    const settings = { name: REPO };
    const tokenForm = { name: 'ci' } as never;
    const tokenPage = { content: [{ id: TOKEN }], page: { number: 1, size: 5, totalElements: 6, totalPages: 2 } };
    const tokenInfo = { token: 'secret' };

    const calls: CallCase<NuGetService>[] = [
      {
        name: 'fetchRepositoryUsage',
        invoke: (s) => from(s.fetchRepositoryUsage()),
        api: () => repoApi.getRepoUsage,
        args: [''],
        response: usage,
        expected: usage,
      },
      {
        name: 'fetchRepositorySettings',
        invoke: (s) => from(s.fetchRepositorySettings()),
        api: () => repoApi.getRepoSettings,
        args: [''],
        response: settings,
        expected: settings,
      },
      {
        name: 'updateRepoSettings',
        invoke: (s) => from(s.updateRepoSettings(settingsForm)),
        api: () => repoApi.updateRepoSettings,
        args: ['', settingsForm],
        response: restResponse(undefined),
        expected: undefined,
      },
      {
        name: 'updateRepo',
        invoke: (s) => from(s.updateRepoDescription(descriptionForm)),
        api: () => repoApi.updateRepo,
        args: ['', descriptionForm],
        response: restResponse(undefined),
        expected: undefined,
      },
      {
        name: 'deleteRepository',
        invoke: (s) => from(s.deleteRepository('other-repo')),
        api: () => repoApi.deleteRepo,
        args: ['other-repo'],
        response: restResponse(undefined),
        expected: undefined,
      },
      {
        name: 'getDeployTokens',
        invoke: (s) => from(s.getDeployTokens(1, 5)),
        api: () => tokenApi.listDeployTokens,
        args: ['', 1, 5],
        response: tokenPage,
        expected: tokenPage,
      },
      {
        name: 'rotateDeployToken',
        invoke: (s) => from(s.rotateDeployToken(TOKEN)),
        api: () => tokenApi.rotateDeployToken,
        args: [TOKEN, ''],
        response: 'rotated',
        expected: 'rotated',
      },
      {
        name: 'createDeployToken',
        invoke: (s) => from(s.createDeployToken(tokenForm)),
        api: () => tokenApi.createDeployToken,
        args: ['', tokenForm],
        response: tokenInfo,
        expected: tokenInfo,
      },
      {
        name: 'revokeDeployToken',
        invoke: (s) => from(s.revokeDeployToken(TOKEN)),
        api: () => tokenApi.revokeDeployToken,
        args: [TOKEN, ''],
        response: restResponse(undefined),
        expected: undefined,
      },
    ];
    describeCalls(() => service, calls);

    it('sends the selected repository name to the repository-scoped calls', async () => {
      await selectRepo(service, repoApi.getRepoPermissions, REPO);
      asSpy(repoApi.getRepoUsage).and.returnValue(of(usage));
      asSpy(tokenApi.revokeDeployToken).and.returnValue(of(restResponse(undefined)));

      await service.fetchRepositoryUsage();
      await service.revokeDeployToken(TOKEN);

      expect(repoApi.getRepoUsage).toHaveBeenCalledOnceWith(REPO);
      expect(tokenApi.revokeDeployToken).toHaveBeenCalledOnceWith(TOKEN, REPO);
    });
  });
});
