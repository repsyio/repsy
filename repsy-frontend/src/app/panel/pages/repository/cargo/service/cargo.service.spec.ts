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
import { of } from 'rxjs';

import { CargoCrateControllerService, ProtocolRepoControllerService } from '../../../../../../generated/api';
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
import { CargoService } from './cargo.service';

const CRATE = 'serde';
const VERSION = '1.0.0';

describe('CargoService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let cargoApi: jasmine.SpyObj<CargoCrateControllerService>;
  let service: CargoService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getPermission']);
    cargoApi = jasmine.createSpyObj<CargoCrateControllerService>('CargoCrateControllerService', [
      'searchCargoCrates',
      'getCargoCrate',
      'getCargoCrateVersion',
      'listCargoCrateVersions',
      'deleteCargoCrate',
      'deleteCargoCrateVersion',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: CargoCrateControllerService, useValue: cargoApi },
      ],
    });
    service = TestBed.inject(CargoService);
  });

  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getPermission,
    probe: (s) => s.deleteCrate(CRATE),
    probeApi: () => cargoApi.deleteCargoCrate,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getPermission, REPO));

    const paged: PagedCase<CargoService>[] = [
      {
        name: 'searchCrates',
        invoke: (s, search) => s.searchCrates(search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => cargoApi.searchCargoCrates,
        // The crate list adds the id as tie-breaker, so a pager keeps one order.
        args: (search) => [{ ...PAGEABLE, sort: ['name,DESC', 'id,DESC'] }, REPO, search],
      },
      {
        name: 'fetchCrateVersions',
        invoke: (s, search) => s.fetchCrateVersions(CRATE, search, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => cargoApi.listCargoCrateVersions,
        args: (search) => [CRATE, PAGEABLE, REPO, search],
      },
    ];
    describePagedCalls(() => service, paged);

    it('searchCrates sorts by the chosen column and then by id in the same direction', () => {
      cargoApi.searchCargoCrates.and.returnValue(of(restResponse({ content: [], page: {} })) as never);

      service
        .searchCrates('', { name: 'Oldest', column: 'lastUpdatedAt', type: 'ASC' }, PAGE_INDEX, PAGE_SIZE)
        .subscribe();

      expect(cargoApi.searchCargoCrates).toHaveBeenCalledWith(
        { page: PAGE_INDEX, size: PAGE_SIZE, sort: ['lastUpdatedAt,ASC', 'id,ASC'] },
        REPO,
        undefined,
      );
    });

    it('searchCrates sorted by id does not repeat the id as tie-breaker', () => {
      cargoApi.searchCargoCrates.and.returnValue(of(restResponse({ content: [], page: {} })) as never);

      service.searchCrates('', { name: 'Newest', column: 'id', type: 'DESC' }, PAGE_INDEX, PAGE_SIZE).subscribe();

      expect(cargoApi.searchCargoCrates.calls.mostRecent().args[0].sort).toEqual(['id,DESC']);
    });

    const crate = { name: CRATE };
    const crateVersion = { name: CRATE, version: VERSION };
    const calls: CallCase<CargoService>[] = [
      {
        name: 'fetchCrate',
        invoke: (s) => s.fetchCrate(CRATE),
        api: () => cargoApi.getCargoCrate,
        args: [CRATE, REPO],
        response: restResponse(crate),
        expected: crate,
      },
      {
        name: 'fetchCrateVersion',
        invoke: (s) => s.fetchCrateVersion(CRATE, VERSION),
        api: () => cargoApi.getCargoCrateVersion,
        args: [CRATE, VERSION, REPO],
        response: restResponse(crateVersion),
        expected: crateVersion,
      },
      {
        name: 'deleteCrate',
        invoke: (s) => s.deleteCrate(CRATE),
        api: () => cargoApi.deleteCargoCrate,
        args: [CRATE, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [cargoApi.deleteCargoCrateVersion],
      },
      {
        name: 'deleteCrateVersion',
        invoke: (s) => s.deleteCrateVersion(CRATE, VERSION),
        api: () => cargoApi.deleteCargoCrateVersion,
        args: [CRATE, VERSION, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [cargoApi.deleteCargoCrate],
      },
    ];
    describeCalls(() => service, calls);
  });
});
