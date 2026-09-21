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

import { ProtocolRepoControllerService, PypiPackageControllerService } from '../../../../../../generated/api';
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
import { PypiService } from './pypi.service';

const PACKAGE = 'requests';
const RELEASE = '2.31.0';

describe('PypiService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let pypiApi: jasmine.SpyObj<PypiPackageControllerService>;
  let service: PypiService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getPermission']);
    pypiApi = jasmine.createSpyObj<PypiPackageControllerService>('PypiPackageControllerService', [
      'listPypiPackages',
      'listReleases',
      'deletePypiPackage',
      'getRelease',
      'deleteRelease',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: PypiPackageControllerService, useValue: pypiApi },
      ],
    });
    service = TestBed.inject(PypiService);
  });

  // PyPI names the entry point selectRepository where the other services call it getRepository.
  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getPermission,
    probe: (s) => s.deletePackage(PACKAGE),
    probeApi: () => pypiApi.deletePypiPackage,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getPermission, REPO));

    const paged: PagedCase<PypiService>[] = [
      {
        name: 'fetchRepositoryPackagesLikeName',
        invoke: (s, name) => s.fetchRepositoryPackagesLikeName(name, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => pypiApi.listPypiPackages,
        args: (name) => [PAGEABLE, REPO, name],
      },
      {
        name: 'fetchPackageReleasesLikeName',
        invoke: (s, version) => s.fetchPackageReleasesLikeName(PACKAGE, version, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => pypiApi.listReleases,
        args: (version) => [PACKAGE, PAGEABLE, REPO, version],
      },
    ];
    describePagedCalls(() => service, paged);

    const release = { version: RELEASE };
    const calls: CallCase<PypiService>[] = [
      {
        name: 'deletePackage',
        invoke: (s) => s.deletePackage(PACKAGE),
        api: () => pypiApi.deletePypiPackage,
        args: [PACKAGE, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [pypiApi.deleteRelease],
      },
      {
        name: 'fetchRelease',
        invoke: (s) => s.fetchRelease(PACKAGE, RELEASE),
        api: () => pypiApi.getRelease,
        args: [PACKAGE, RELEASE, REPO],
        response: restResponse(release),
        expected: release,
      },
      {
        name: 'deleteRelease',
        invoke: (s) => s.deleteRelease(PACKAGE, RELEASE),
        api: () => pypiApi.deleteRelease,
        args: [PACKAGE, RELEASE, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [pypiApi.deletePypiPackage],
      },
    ];
    describeCalls(() => service, calls);
  });
});
