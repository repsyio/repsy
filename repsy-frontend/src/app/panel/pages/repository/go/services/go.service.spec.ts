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

import { TestBed } from '@angular/core/testing';

import { GoModulesApi, ReposApi } from '../../../../../../generated/api';
import {
  CallCase,
  describeCalls,
  describePagedCalls,
  describeRepoSelection,
  PAGE_ARGS,
  PAGE_INDEX,
  PAGE_SIZE,
  PagedCase,
  REPO,
  selectRepo,
  SORT,
} from '../../testing/protocol-service-spec-helpers';
import { GoService } from './go.service';

const MODULE = 'github.com/acme/widget';
const VERSION = 'v1.2.3';

describe('GoService', () => {
  let repoApi: jasmine.SpyObj<ReposApi>;
  let goApi: jasmine.SpyObj<GoModulesApi>;
  let service: GoService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ReposApi>('ReposApi', ['getRepoPermissions']);
    goApi = jasmine.createSpyObj<GoModulesApi>('GoModulesApi', [
      'listGolangModules',
      'deleteGolangModule',
      'listGolangModuleVersions',
      'getGolangModuleInfo',
      'deleteGolangModuleVersion',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ReposApi, useValue: repoApi },
        { provide: GoModulesApi, useValue: goApi },
      ],
    });
    service = TestBed.inject(GoService);
  });

  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getRepoPermissions,
    probe: (s) => s.deleteModule(MODULE),
    probeApi: () => goApi.deleteGolangModule,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getRepoPermissions, REPO));

    const paged: PagedCase<GoService>[] = [
      {
        name: 'fetchModules',
        invoke: (s, search) => s.fetchModules(search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => goApi.listGolangModules,
        args: (search) => [REPO, search, ...PAGE_ARGS],
        bare: true,
      },
      {
        name: 'fetchModuleVersions',
        invoke: (s, search) => s.fetchModuleVersions(MODULE, search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => goApi.listGolangModuleVersions,
        args: (search) => [MODULE, REPO, search, ...PAGE_ARGS],
        bare: true,
      },
    ];
    describePagedCalls(() => service, paged);

    const info = { modulePath: MODULE };
    const calls: CallCase<GoService>[] = [
      {
        name: 'deleteModule',
        invoke: (s) => s.deleteModule(MODULE),
        api: () => goApi.deleteGolangModule,
        args: [MODULE, REPO],
        response: undefined,
        expected: undefined,
        notCalled: () => [goApi.deleteGolangModuleVersion],
      },
      {
        name: 'fetchModuleInfo',
        invoke: (s) => s.fetchModuleInfo(MODULE),
        api: () => goApi.getGolangModuleInfo,
        args: [MODULE, REPO],
        response: info,
        expected: info,
      },
      {
        name: 'deleteModuleVersion',
        invoke: (s) => s.deleteModuleVersion(MODULE, VERSION),
        api: () => goApi.deleteGolangModuleVersion,
        args: [MODULE, VERSION, REPO],
        response: undefined,
        expected: undefined,
        notCalled: () => [goApi.deleteGolangModule],
      },
    ];
    describeCalls(() => service, calls);
  });
});
