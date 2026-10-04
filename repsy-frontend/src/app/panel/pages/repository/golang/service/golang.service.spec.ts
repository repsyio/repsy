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

import { GolangModuleControllerService, ProtocolRepoControllerService } from '../../../../../../generated/api';
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
import { GolangService } from './golang.service';

const MODULE = 'github.com/acme/widget';
const VERSION = 'v1.2.3';

describe('GolangService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let golangApi: jasmine.SpyObj<GolangModuleControllerService>;
  let service: GolangService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'getRepoPermissions',
    ]);
    golangApi = jasmine.createSpyObj<GolangModuleControllerService>('GolangModuleControllerService', [
      'listGolangModules',
      'deleteGolangModule',
      'listGolangModuleVersions',
      'getGolangModuleInfo',
      'deleteGolangModuleVersion',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: GolangModuleControllerService, useValue: golangApi },
      ],
    });
    service = TestBed.inject(GolangService);
  });

  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getRepoPermissions,
    probe: (s) => s.deleteModule(MODULE),
    probeApi: () => golangApi.deleteGolangModule,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getRepoPermissions, REPO));

    const paged: PagedCase<GolangService>[] = [
      {
        name: 'fetchModules',
        invoke: (s, search) => s.fetchModules(search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => golangApi.listGolangModules,
        args: (search) => [REPO, search, ...PAGE_ARGS],
        bare: true,
      },
      {
        name: 'fetchModuleVersions',
        invoke: (s, search) => s.fetchModuleVersions(MODULE, search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => golangApi.listGolangModuleVersions,
        args: (search) => [MODULE, REPO, search, ...PAGE_ARGS],
        bare: true,
      },
    ];
    describePagedCalls(() => service, paged);

    const info = { modulePath: MODULE };
    const calls: CallCase<GolangService>[] = [
      {
        name: 'deleteModule',
        invoke: (s) => s.deleteModule(MODULE),
        api: () => golangApi.deleteGolangModule,
        args: [MODULE, REPO],
        response: undefined,
        expected: undefined,
        notCalled: () => [golangApi.deleteGolangModuleVersion],
      },
      {
        name: 'fetchModuleInfo',
        invoke: (s) => s.fetchModuleInfo(MODULE),
        api: () => golangApi.getGolangModuleInfo,
        args: [MODULE, REPO],
        response: info,
        expected: info,
      },
      {
        name: 'deleteModuleVersion',
        invoke: (s) => s.deleteModuleVersion(MODULE, VERSION),
        api: () => golangApi.deleteGolangModuleVersion,
        args: [MODULE, VERSION, REPO],
        response: undefined,
        expected: undefined,
        notCalled: () => [golangApi.deleteGolangModule],
      },
    ];
    describeCalls(() => service, calls);
  });
});
