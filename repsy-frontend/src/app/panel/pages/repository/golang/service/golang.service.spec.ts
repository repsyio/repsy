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
  PAGE_INDEX,
  PAGE_SIZE,
  PAGEABLE,
  PagedCase,
  REPO,
  restResponse,
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
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getPermission']);
    golangApi = jasmine.createSpyObj<GolangModuleControllerService>('GolangModuleControllerService', [
      'listGolangModules',
      'searchGolangModules',
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
    getPermission: () => repoApi.getPermission,
    probe: (s) => s.deleteModule(MODULE),
    probeApi: () => golangApi.deleteGolangModule,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getPermission, REPO));

    const paged: PagedCase<GolangService>[] = [
      {
        // Lists without a search term, so there is no fallback to check.
        name: 'fetchModules',
        invoke: (s) => s.fetchModules(SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => golangApi.listGolangModules,
        args: () => [PAGEABLE, REPO],
        searchable: false,
        notCalled: () => [golangApi.searchGolangModules],
      },
      {
        name: 'searchModules',
        invoke: (s, search) => s.searchModules(search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => golangApi.searchGolangModules,
        args: (search) => [PAGEABLE, REPO, search],
        notCalled: () => [golangApi.listGolangModules],
      },
      {
        name: 'fetchModuleVersions',
        invoke: (s, search) => s.fetchModuleVersions(MODULE, search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => golangApi.listGolangModuleVersions,
        args: (search) => [MODULE, PAGEABLE, REPO, search],
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
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [golangApi.deleteGolangModuleVersion],
      },
      {
        name: 'fetchModuleInfo',
        invoke: (s) => s.fetchModuleInfo(MODULE),
        api: () => golangApi.getGolangModuleInfo,
        args: [MODULE, REPO],
        response: restResponse(info),
        expected: info,
      },
      {
        name: 'deleteModuleVersion',
        invoke: (s) => s.deleteModuleVersion(MODULE, VERSION),
        api: () => golangApi.deleteGolangModuleVersion,
        args: [MODULE, VERSION, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [golangApi.deleteGolangModule],
      },
    ];
    describeCalls(() => service, calls);
  });
});
