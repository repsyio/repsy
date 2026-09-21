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

import { ProtocolRepoControllerService, RubyGemApiControllerService } from '../../../../../../generated/api';
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
import { RubyService } from './ruby.service';

const GEM = 'rails';
const VERSION = '7.1.0';
const PLATFORM = 'java';

describe('RubyService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let rubyApi: jasmine.SpyObj<RubyGemApiControllerService>;
  let service: RubyService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getPermission']);
    rubyApi = jasmine.createSpyObj<RubyGemApiControllerService>('RubyGemApiControllerService', [
      'listGems',
      'listGemVersions',
      'getGemVersion',
      'deleteGem',
      'deleteGemVersion',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: RubyGemApiControllerService, useValue: rubyApi },
      ],
    });
    service = TestBed.inject(RubyService);
  });

  // RubyService has no resetActiveRepoIfChanged, so this pins the current behaviour (RPS-1159).
  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getPermission,
    probe: (s) => s.deleteGem(GEM),
    probeApi: () => rubyApi.deleteGem,
    probeRepoArg: 1,
    resetsOnChange: false,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getPermission, REPO));

    const paged: PagedCase<RubyService>[] = [
      {
        name: 'searchGems',
        invoke: (s, search) => s.searchGems(search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => rubyApi.listGems,
        args: (search) => [PAGEABLE, REPO, search],
      },
      {
        name: 'fetchGemVersions',
        invoke: (s, search) => s.fetchGemVersions(GEM, search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => rubyApi.listGemVersions,
        args: (search) => [GEM, PAGEABLE, REPO, search],
      },
    ];
    describePagedCalls(() => service, paged);

    const info = { version: VERSION };
    const calls: CallCase<RubyService>[] = [
      {
        name: 'fetchGemVersion with a platform',
        invoke: (s) => s.fetchGemVersion(GEM, VERSION, PLATFORM),
        api: () => rubyApi.getGemVersion,
        args: [GEM, VERSION, REPO, PLATFORM],
        response: restResponse(info),
        expected: info,
      },
      {
        name: 'fetchGemVersion without a platform',
        invoke: (s) => s.fetchGemVersion(GEM, VERSION),
        api: () => rubyApi.getGemVersion,
        args: [GEM, VERSION, REPO, undefined],
        response: restResponse(info),
        expected: info,
      },
      {
        name: 'deleteGem',
        invoke: (s) => s.deleteGem(GEM),
        api: () => rubyApi.deleteGem,
        args: [GEM, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [rubyApi.deleteGemVersion],
      },
      {
        name: 'deleteGemVersion',
        invoke: (s) => s.deleteGemVersion(GEM, VERSION, PLATFORM),
        api: () => rubyApi.deleteGemVersion,
        args: [GEM, VERSION, REPO, PLATFORM],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [rubyApi.deleteGem],
      },
    ];
    describeCalls(() => service, calls);
  });
});
