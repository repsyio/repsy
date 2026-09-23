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
import { firstValueFrom, Observable, of } from 'rxjs';

import {
  PagedModelVulnerabilityScanInfo,
  RepoSecurityDetail,
  RepoSecuritySummary,
  RepoType,
  SecurityScanControllerService,
  SecurityScansSummary,
  Severity,
  VersionSecuritySummary,
  VulnerabilityScanControllerService,
} from '../../../../../generated/api';
import { describeNoAuthorizationHeader } from '../../../shared/testing/authorization-header-spec-helpers';
import { CallCase, describeCalls, restResponse } from '../../repository/testing/protocol-service-spec-helpers';
import { SecurityService } from './security.service';

const REPO = 'acme-repo';
const ARTIFACT = 'acme-artifact';
const SCANS: PagedModelVulnerabilityScanInfo = {
  content: [{ id: 'scan-1', repoName: REPO }] as PagedModelVulnerabilityScanInfo['content'],
  page: { number: 1, size: 20, totalElements: 21, totalPages: 2 },
};
const SUMMARY: Record<string, RepoSecuritySummary> = { [REPO]: { severity: Severity.High, scanned: true } };
const VERSION_SUMMARY: Record<string, VersionSecuritySummary> = {
  '1.0.0': { severity: Severity.Low, findingCount: 2, scanned: true },
};
const DETAIL: RepoSecurityDetail = { criticalCount: 1, highCount: 2, totalCount: 3 };
const SCANS_SUMMARY: SecurityScansSummary = { criticalCount: 1, highCount: 2, totalCount: 3 };

describe('SecurityService', () => {
  let scanApi: jasmine.SpyObj<SecurityScanControllerService>;
  let vulnerabilityApi: jasmine.SpyObj<VulnerabilityScanControllerService>;
  let service: SecurityService;

  beforeEach(() => {
    scanApi = jasmine.createSpyObj<SecurityScanControllerService>('SecurityScanControllerService', [
      'listSecurityScans',
      'getSecurityScansSummary',
    ]);
    vulnerabilityApi = jasmine.createSpyObj<VulnerabilityScanControllerService>('VulnerabilityScanControllerService', [
      'getSecuritySummary',
      'getVersionSecuritySummary',
      'getArtifactSecuritySummary',
      'getRepoSecurityDetail',
      'getArtifactSecurityDetail',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: SecurityScanControllerService, useValue: scanApi },
        { provide: VulnerabilityScanControllerService, useValue: vulnerabilityApi },
      ],
    });
    service = TestBed.inject(SecurityService);
  });

  // No call passes an `authorization` argument: the generated client has no such parameter for any operation, so the
  // toHaveBeenCalledOnceWith arguments below double as the "no header" assertion (RPS-1161).
  const cases: CallCase<SecurityService>[] = [
    {
      name: 'listScans with every filter',
      invoke: (s) => s.listScans(Severity.High, RepoType.Maven, REPO, 1, 20),
      api: () => scanApi.listSecurityScans,
      args: ['HIGH', 'MAVEN', REPO, 1, 20],
      response: restResponse(SCANS),
      expected: SCANS,
    },
    {
      name: 'listScans without filters',
      invoke: (s) => s.listScans(),
      api: () => scanApi.listSecurityScans,
      args: [undefined, undefined, undefined, undefined, undefined],
      response: restResponse(SCANS),
      expected: SCANS,
    },
    {
      name: 'getSecuritySummary for some repositories',
      invoke: (s) => s.getSecuritySummary([REPO, 'other-repo']),
      api: () => vulnerabilityApi.getSecuritySummary,
      args: [[REPO, 'other-repo']],
      response: restResponse(SUMMARY),
      expected: SUMMARY,
    },
    {
      name: 'getSecuritySummary for every repository',
      invoke: (s) => s.getSecuritySummary(),
      api: () => vulnerabilityApi.getSecuritySummary,
      args: [undefined],
      response: restResponse(SUMMARY),
      expected: SUMMARY,
    },
    {
      // The service takes (repoName, artifactName), the generated client (artifactName, repoName).
      name: 'getVersionSecuritySummary',
      invoke: (s) => s.getVersionSecuritySummary(REPO, ARTIFACT),
      api: () => vulnerabilityApi.getVersionSecuritySummary,
      args: [ARTIFACT, REPO],
      response: restResponse(VERSION_SUMMARY),
      expected: VERSION_SUMMARY,
    },
    {
      name: 'getArtifactSecuritySummary',
      invoke: (s) => s.getArtifactSecuritySummary(REPO),
      api: () => vulnerabilityApi.getArtifactSecuritySummary,
      args: [REPO],
      response: restResponse(VERSION_SUMMARY),
      expected: VERSION_SUMMARY,
    },
    {
      name: 'getRepoSecurityDetail',
      invoke: (s) => s.getRepoSecurityDetail(REPO),
      api: () => vulnerabilityApi.getRepoSecurityDetail,
      args: [REPO],
      response: restResponse(DETAIL),
      expected: DETAIL,
    },
    {
      // The service takes (repoName, artifactName), the generated client (artifactName, repoName).
      name: 'getArtifactSecurityDetail',
      invoke: (s) => s.getArtifactSecurityDetail(REPO, ARTIFACT),
      api: () => vulnerabilityApi.getArtifactSecurityDetail,
      args: [ARTIFACT, REPO],
      response: restResponse(DETAIL),
      expected: DETAIL,
    },
    {
      name: 'getScansSummary with a repository type and a repository',
      invoke: (s) => s.getScansSummary(RepoType.Npm, REPO),
      api: () => scanApi.getSecurityScansSummary,
      args: ['NPM', REPO],
      response: restResponse(SCANS_SUMMARY),
      expected: SCANS_SUMMARY,
    },
    {
      name: 'getScansSummary without filters',
      invoke: (s) => s.getScansSummary(),
      api: () => scanApi.getSecurityScansSummary,
      args: [undefined, undefined],
      response: restResponse(SCANS_SUMMARY),
      expected: SCANS_SUMMARY,
    },
  ];
  describeCalls(() => service, cases);

  describe('the maps default to an empty object when the response has no data', () => {
    const emptyMapCalls: { name: string; api: () => jasmine.Spy; invoke: () => Observable<unknown> }[] = [
      {
        name: 'getSecuritySummary',
        api: () => vulnerabilityApi.getSecuritySummary,
        invoke: () => service.getSecuritySummary(),
      },
      {
        name: 'getVersionSecuritySummary',
        api: () => vulnerabilityApi.getVersionSecuritySummary,
        invoke: () => service.getVersionSecuritySummary(REPO, ARTIFACT),
      },
      {
        name: 'getArtifactSecuritySummary',
        api: () => vulnerabilityApi.getArtifactSecuritySummary,
        invoke: () => service.getArtifactSecuritySummary(REPO),
      },
    ];

    for (const c of emptyMapCalls) {
      for (const data of [undefined, null]) {
        it(`${c.name} answers {} for data ${data}`, async () => {
          c.api().and.returnValue(of(restResponse(data)));

          expect(await firstValueFrom(c.invoke())).toEqual({});
        });
      }
    }
  });

  describe('the single-object calls pass a missing payload through', () => {
    // No fallback there: an empty response is handed to the caller as undefined (the non-null assertion is a cast).
    const objectCalls: { name: string; api: () => jasmine.Spy; invoke: () => Observable<unknown> }[] = [
      {
        name: 'getRepoSecurityDetail',
        api: () => vulnerabilityApi.getRepoSecurityDetail,
        invoke: () => service.getRepoSecurityDetail(REPO),
      },
      {
        name: 'getArtifactSecurityDetail',
        api: () => vulnerabilityApi.getArtifactSecurityDetail,
        invoke: () => service.getArtifactSecurityDetail(REPO, ARTIFACT),
      },
      {
        name: 'getScansSummary',
        api: () => scanApi.getSecurityScansSummary,
        invoke: () => service.getScansSummary(),
      },
    ];

    for (const c of objectCalls) {
      it(`${c.name} emits undefined`, async () => {
        c.api().and.returnValue(of(restResponse(undefined)));

        expect(await firstValueFrom(c.invoke())).toBeUndefined();
      });
    }
  });

  describeNoAuthorizationHeader({
    api: () => scanApi.listSecurityScans,
    invoke: () => service.listScans(),
  });
});
