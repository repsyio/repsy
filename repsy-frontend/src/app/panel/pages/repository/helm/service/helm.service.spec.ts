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

import { HelmChartControllerService, ProtocolRepoControllerService } from '../../../../../../generated/api';
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
import { HelmService } from './helm.service';

const CHART = 'nginx';
const VERSION = '1.2.3';

describe('HelmService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let helmApi: jasmine.SpyObj<HelmChartControllerService>;
  let service: HelmService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getPermission']);
    helmApi = jasmine.createSpyObj<HelmChartControllerService>('HelmChartControllerService', [
      'searchHelmCharts',
      'getHelmChartVersions',
      'getHelmChartDetail',
      'deleteAllHelmChartVersions',
      'deleteHelmChart',
      'getHelmChartOciTags',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: HelmChartControllerService, useValue: helmApi },
      ],
    });
    service = TestBed.inject(HelmService);
  });

  // HelmService has no resetActiveRepoIfChanged (unlike npm, Maven, Docker and PyPI), so this pins the current
  // behaviour (RPS-1159): the previous repository's name is used until the new permission arrives.
  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getPermission,
    probe: (s) => s.getChartVersions(CHART),
    probeApi: () => helmApi.getHelmChartVersions,
    probeRepoArg: 0,
    resetsOnChange: false,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getPermission, REPO));

    const paged: PagedCase<HelmService>[] = [
      {
        name: 'searchCharts',
        invoke: (s, query) => s.searchCharts(query, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => helmApi.searchHelmCharts,
        args: (query) => [PAGEABLE, REPO, query],
      },
    ];
    describePagedCalls(() => service, paged);

    const versions = [{ version: VERSION }];
    const detail = { name: CHART, version: VERSION };
    const tags = ['1.2.3', 'latest'];
    // Unlike the other services, the Helm client takes the repository name first.
    const calls: CallCase<HelmService>[] = [
      {
        name: 'getChartVersions',
        invoke: (s) => s.getChartVersions(CHART),
        api: () => helmApi.getHelmChartVersions,
        args: [REPO, CHART],
        response: restResponse(versions),
        expected: versions,
      },
      {
        name: 'getChartVersions without version data',
        invoke: (s) => s.getChartVersions(CHART),
        api: () => helmApi.getHelmChartVersions,
        args: [REPO, CHART],
        response: restResponse(undefined),
        expected: [],
      },
      {
        name: 'getChartDetail',
        invoke: (s) => s.getChartDetail(CHART, VERSION),
        api: () => helmApi.getHelmChartDetail,
        args: [REPO, CHART, VERSION],
        response: restResponse(detail),
        expected: detail,
      },
      {
        name: 'deleteAllVersions',
        invoke: (s) => s.deleteAllVersions(CHART),
        api: () => helmApi.deleteAllHelmChartVersions,
        args: [REPO, CHART],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [helmApi.deleteHelmChart],
      },
      {
        name: 'deleteChart (one version)',
        invoke: (s) => s.deleteChart(CHART, VERSION),
        api: () => helmApi.deleteHelmChart,
        args: [REPO, CHART, VERSION],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [helmApi.deleteAllHelmChartVersions],
      },
      {
        name: 'getOciTags',
        invoke: (s) => s.getOciTags(CHART),
        api: () => helmApi.getHelmChartOciTags,
        args: [REPO, CHART],
        response: restResponse(tags),
        expected: tags,
      },
      {
        name: 'getOciTags without tag data',
        invoke: (s) => s.getOciTags(CHART),
        api: () => helmApi.getHelmChartOciTags,
        args: [REPO, CHART],
        response: restResponse(undefined),
        expected: [],
      },
    ];
    describeCalls(() => service, calls);
  });
});
