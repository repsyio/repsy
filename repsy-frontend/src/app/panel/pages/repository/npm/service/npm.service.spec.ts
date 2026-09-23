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

import {
  NpmPackageApiControllerService,
  NpmScopeApiControllerService,
  ProtocolRepoControllerService,
} from '../../../../../../generated/api';
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
import { NpmService } from './npm.service';

const SCOPE = 'acme';
const PACKAGE = 'widget';
const VERSION = '1.2.3';

describe('NpmService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let packageApi: jasmine.SpyObj<NpmPackageApiControllerService>;
  let scopeApi: jasmine.SpyObj<NpmScopeApiControllerService>;
  let service: NpmService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getPermission']);
    packageApi = jasmine.createSpyObj<NpmPackageApiControllerService>('NpmPackageApiControllerService', [
      'listNpmPackages',
      'listNpmScopedPackageVersions',
      'listNpmPackageVersions',
      'listNpmScopedPackageTags',
      'listNpmPackageTags',
      'getNpmScopedPackageVersion',
      'getNpmPackageVersion',
      'deleteScopedNpmPackage',
      'deleteNpmPackage',
      'deleteNpmScopedPackageVersion',
      'deleteNpmPackageVersion',
    ]);
    scopeApi = jasmine.createSpyObj<NpmScopeApiControllerService>('NpmScopeApiControllerService', [
      'listNpmPackagesByScope',
      'listUnscopedNpmPackages',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: NpmPackageApiControllerService, useValue: packageApi },
        { provide: NpmScopeApiControllerService, useValue: scopeApi },
      ],
    });
    service = TestBed.inject(NpmService);
  });

  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getPermission,
    probe: (s) => s.fetchPackageTags(PACKAGE, ''),
    probeApi: () => packageApi.listNpmPackageTags,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getPermission, REPO));

    const paged: PagedCase<NpmService>[] = [
      {
        name: 'searchPackages',
        invoke: (s, scope) => s.searchPackages(scope, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => packageApi.listNpmPackages,
        args: (scope) => [PAGEABLE, REPO, scope],
      },
      {
        name: 'searchScopedPackages',
        invoke: (s, name) => s.searchScopedPackages(SCOPE, name, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => scopeApi.listNpmPackagesByScope,
        args: (name) => [SCOPE, PAGEABLE, REPO, name],
      },
      {
        name: 'searchUnscopedPackages',
        invoke: (s, name) => s.searchUnscopedPackages(name, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => scopeApi.listUnscopedNpmPackages,
        args: (name) => [PAGEABLE, REPO, name],
      },
      {
        name: 'searchPackageVersions of a scoped package',
        invoke: (s, version) => s.searchPackageVersions(PACKAGE, SCOPE, version, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => packageApi.listNpmScopedPackageVersions,
        args: (version) => [SCOPE, PACKAGE, PAGEABLE, REPO, version],
        notCalled: () => [packageApi.listNpmPackageVersions],
      },
      {
        name: 'searchPackageVersions of an unscoped package',
        invoke: (s, version) => s.searchPackageVersions(PACKAGE, '', version, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => packageApi.listNpmPackageVersions,
        args: (version) => [PACKAGE, PAGEABLE, REPO, version],
        notCalled: () => [packageApi.listNpmScopedPackageVersions],
      },
    ];
    describePagedCalls(() => service, paged);

    const tags = [{ tag: 'latest', version: VERSION }];
    const detail = { name: PACKAGE, version: VERSION };
    const calls: CallCase<NpmService>[] = [
      {
        name: 'fetchPackageTags of a scoped package',
        invoke: (s) => s.fetchPackageTags(PACKAGE, SCOPE),
        api: () => packageApi.listNpmScopedPackageTags,
        args: [SCOPE, PACKAGE, REPO],
        response: restResponse(tags),
        expected: tags,
        notCalled: () => [packageApi.listNpmPackageTags],
      },
      {
        name: 'fetchPackageTags of an unscoped package',
        invoke: (s) => s.fetchPackageTags(PACKAGE, ''),
        api: () => packageApi.listNpmPackageTags,
        args: [PACKAGE, REPO],
        response: restResponse(tags),
        expected: tags,
        notCalled: () => [packageApi.listNpmScopedPackageTags],
      },
      {
        name: 'fetchPackageTags without tag data',
        invoke: (s) => s.fetchPackageTags(PACKAGE, ''),
        api: () => packageApi.listNpmPackageTags,
        args: [PACKAGE, REPO],
        response: restResponse(undefined),
        expected: [],
      },
      {
        name: 'fetchPackageVersion of a scoped package',
        invoke: (s) => s.fetchPackageVersion(PACKAGE, SCOPE, VERSION),
        api: () => packageApi.getNpmScopedPackageVersion,
        args: [SCOPE, PACKAGE, VERSION, REPO],
        response: restResponse(detail),
        expected: detail,
        notCalled: () => [packageApi.getNpmPackageVersion],
      },
      {
        name: 'fetchPackageVersion of an unscoped package',
        invoke: (s) => s.fetchPackageVersion(PACKAGE, '', VERSION),
        api: () => packageApi.getNpmPackageVersion,
        args: [PACKAGE, VERSION, REPO],
        response: restResponse(detail),
        expected: detail,
        notCalled: () => [packageApi.getNpmScopedPackageVersion],
      },
      {
        name: 'deletePackage of a scoped package',
        invoke: (s) => s.deletePackage(PACKAGE, SCOPE),
        api: () => packageApi.deleteScopedNpmPackage,
        args: [SCOPE, PACKAGE, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [packageApi.deleteNpmPackage],
      },
      {
        name: 'deletePackage of an unscoped package',
        invoke: (s) => s.deletePackage(PACKAGE, ''),
        api: () => packageApi.deleteNpmPackage,
        args: [PACKAGE, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [packageApi.deleteScopedNpmPackage],
      },
      {
        name: 'deletePackageVersion of a scoped package',
        invoke: (s) => s.deletePackageVersion(PACKAGE, SCOPE, VERSION),
        api: () => packageApi.deleteNpmScopedPackageVersion,
        args: [SCOPE, PACKAGE, VERSION, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [packageApi.deleteNpmPackageVersion],
      },
      {
        name: 'deletePackageVersion of an unscoped package',
        invoke: (s) => s.deletePackageVersion(PACKAGE, '', VERSION),
        api: () => packageApi.deleteNpmPackageVersion,
        args: [PACKAGE, VERSION, REPO],
        response: restResponse('ignored'),
        expected: undefined,
        notCalled: () => [packageApi.deleteNpmScopedPackageVersion],
      },
    ];
    describeCalls(() => service, calls);
  });
});
