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

import {
  NugetPackageControllerService,
  ProtocolDeployTokenControllerService,
  ProtocolRepoControllerService,
} from '../../../../../../generated/api';
import {
  CallCase,
  collect,
  describeCalls,
  describePagedCalls,
  describeRepoSelection,
  httpError,
  PAGE_INDEX,
  PAGE_SIZE,
  PAGEABLE,
  PagedCase,
  permission,
  REPO,
  restResponse,
  selectRepo,
  SORT,
} from '../../testing/protocol-service-spec-helpers';
import { NugetService } from './nuget.service';

/** The generated methods are overloaded on `observe`, which `and.returnValue` cannot resolve. */
function asSpy(method: unknown): jasmine.Spy {
  return method as jasmine.Spy;
}

const PACKAGE = 'Acme.Lib';
const VERSION = '1.2.3';
const TOKEN = 'token-1';

describe('NugetService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let tokenApi: jasmine.SpyObj<ProtocolDeployTokenControllerService>;
  let nugetApi: jasmine.SpyObj<NugetPackageControllerService>;
  let service: NugetService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'getPermission',
      'getUsage',
      'getSettings',
      'updateSettings',
      'rename',
      'updateDescription',
      'deleteRepo',
    ]);
    tokenApi = jasmine.createSpyObj<ProtocolDeployTokenControllerService>('ProtocolDeployTokenControllerService', [
      'listDeployTokens',
      'rotate',
      'createDeployToken',
      'revoke',
    ]);
    nugetApi = jasmine.createSpyObj<NugetPackageControllerService>('NugetPackageControllerService', [
      'searchNugetPackages',
      'getNugetPackage',
      'listNugetVersions',
      'getNugetVersion',
      'deleteNugetPackage',
      'deleteNugetVersion',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: ProtocolDeployTokenControllerService, useValue: tokenApi },
        { provide: NugetPackageControllerService, useValue: nugetApi },
      ],
    });
    service = TestBed.inject(NugetService);
  });

  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getPermission,
    probe: (s) => from(s.fetchPackage(PACKAGE)),
    probeApi: () => nugetApi.getNugetPackage,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('updateRepositoryName', () => {
    const form = { name: 'renamed-repo' };

    it('sends the rename for the active repository and re-emits it under the new name', async () => {
      await selectRepo(service, repoApi.getPermission, REPO, { canManage: true });
      const emissions = collect(service.repoChanges);
      asSpy(repoApi.rename).and.returnValue(of(restResponse(undefined)));

      await service.updateRepositoryName(form);

      expect(repoApi.rename).toHaveBeenCalledOnceWith(REPO, form);
      expect(emissions.at(-1)).toEqual(permission('renamed-repo', { canManage: true }));
    });

    it('sends the calls that follow to the new name', async () => {
      await selectRepo(service, repoApi.getPermission, REPO);
      asSpy(repoApi.rename).and.returnValue(of(restResponse(undefined)));
      await service.updateRepositoryName(form);
      asSpy(nugetApi.getNugetPackage).and.returnValue(of(restResponse(null)));

      await service.fetchPackage(PACKAGE);

      expect(nugetApi.getNugetPackage).toHaveBeenCalledOnceWith(PACKAGE, 'renamed-repo');
    });

    it('keeps the old name and emits nothing when the rename fails', async () => {
      await selectRepo(service, repoApi.getPermission, REPO);
      const emissions = collect(service.repoChanges);
      const error = httpError(409);
      asSpy(repoApi.rename).and.returnValue(throwError(() => error));

      await expectAsync(service.updateRepositoryName(form)).toBeRejectedWith(error);

      expect(emissions.length).withContext('only the replayed current value').toBe(1);
      expect(emissions[0]?.repoName).toBe(REPO);
    });

    it('sends the rename without throwing when no repository is selected', async () => {
      asSpy(repoApi.rename).and.returnValue(of(restResponse(undefined)));

      await service.updateRepositoryName(form);

      expect(repoApi.rename).toHaveBeenCalledOnceWith('', form);
      expect(collect(service.repoChanges)).toEqual([null]);
    });
  });

  describe('package calls', () => {
    const paged: PagedCase<NugetService>[] = [
      {
        name: 'fetchRepositoryPackages',
        invoke: (s, query) => from(s.fetchRepositoryPackages(query, SORT, PAGE_INDEX, PAGE_SIZE)),
        api: () => nugetApi.searchNugetPackages,
        args: (query) => [PAGEABLE, '', query],
      },
      {
        name: 'fetchPackageVersions',
        invoke: (s, query) => from(s.fetchPackageVersions(PACKAGE, query, SORT, PAGE_INDEX, PAGE_SIZE)),
        api: () => nugetApi.listNugetVersions,
        args: (query) => [PACKAGE, PAGEABLE, '', query],
      },
    ];
    describePagedCalls(() => service, paged);

    const info = { packageId: PACKAGE };
    const versionInfo = { packageId: PACKAGE, version: VERSION };
    const calls: CallCase<NugetService>[] = [
      {
        name: 'fetchPackage',
        invoke: (s) => from(s.fetchPackage(PACKAGE)),
        api: () => nugetApi.getNugetPackage,
        args: [PACKAGE, ''],
        response: restResponse(info),
        expected: info,
      },
      {
        name: 'fetchPackageVersion',
        invoke: (s) => from(s.fetchPackageVersion(PACKAGE, VERSION)),
        api: () => nugetApi.getNugetVersion,
        args: [PACKAGE, VERSION, ''],
        response: restResponse(versionInfo),
        expected: versionInfo,
      },
      {
        name: 'deletePackage',
        invoke: (s) => from(s.deletePackage(PACKAGE)),
        api: () => nugetApi.deleteNugetPackage,
        args: [PACKAGE, ''],
        response: restResponse('PACKAGE'),
        expected: 'PACKAGE',
        notCalled: () => [nugetApi.deleteNugetVersion],
      },
      {
        name: 'deletePackageVersion',
        invoke: (s) => from(s.deletePackageVersion(PACKAGE, VERSION)),
        api: () => nugetApi.deleteNugetVersion,
        args: [PACKAGE, VERSION, ''],
        response: restResponse('VERSION'),
        expected: 'VERSION',
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

    const calls: CallCase<NugetService>[] = [
      {
        name: 'fetchRepositoryUsage',
        invoke: (s) => from(s.fetchRepositoryUsage()),
        api: () => repoApi.getUsage,
        args: [''],
        response: restResponse(usage),
        expected: usage,
      },
      {
        name: 'fetchRepositorySettings',
        invoke: (s) => from(s.fetchRepositorySettings()),
        api: () => repoApi.getSettings,
        args: [''],
        response: restResponse(settings),
        expected: settings,
      },
      {
        name: 'updateRepoSettings',
        invoke: (s) => from(s.updateRepoSettings(settingsForm)),
        api: () => repoApi.updateSettings,
        args: ['', settingsForm],
        response: restResponse(undefined),
        expected: undefined,
      },
      {
        name: 'updateRepoDescription',
        invoke: (s) => from(s.updateRepoDescription(descriptionForm)),
        api: () => repoApi.updateDescription,
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
        args: [{ page: 1, size: 5 }, ''],
        response: restResponse(tokenPage),
        expected: tokenPage,
      },
      {
        name: 'rotateDeployToken',
        invoke: (s) => from(s.rotateDeployToken(TOKEN)),
        api: () => tokenApi.rotate,
        args: [TOKEN, ''],
        response: restResponse('rotated'),
        expected: 'rotated',
      },
      {
        name: 'createDeployToken',
        invoke: (s) => from(s.createDeployToken(tokenForm)),
        api: () => tokenApi.createDeployToken,
        args: ['', tokenForm],
        response: restResponse(tokenInfo),
        expected: tokenInfo,
      },
      {
        name: 'revokeDeployToken',
        invoke: (s) => from(s.revokeDeployToken(TOKEN)),
        api: () => tokenApi.revoke,
        args: [TOKEN, ''],
        response: restResponse(undefined),
        expected: undefined,
      },
    ];
    describeCalls(() => service, calls);

    it('sends the selected repository name to the repository-scoped calls', async () => {
      await selectRepo(service, repoApi.getPermission, REPO);
      asSpy(repoApi.getUsage).and.returnValue(of(restResponse(usage)));
      asSpy(tokenApi.revoke).and.returnValue(of(restResponse(undefined)));

      await service.fetchRepositoryUsage();
      await service.revokeDeployToken(TOKEN);

      expect(repoApi.getUsage).toHaveBeenCalledOnceWith(REPO);
      expect(tokenApi.revoke).toHaveBeenCalledOnceWith(TOKEN, REPO);
    });
  });
});
