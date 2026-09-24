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
  MavenArtifactControllerService,
  ProtocolRepoControllerService,
  RepoSettingsForm,
} from '../../../../../../generated/api';
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
  restResponse,
  selectRepo,
  SORT,
} from '../../testing/protocol-service-spec-helpers';
import { MavenService } from './maven.service';

const GROUP = 'io.acme';
const ARTIFACT = 'widget';
const VERSION = '1.2.3';
const PATH = 'io/acme/widget';

describe('MavenService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let mavenApi: jasmine.SpyObj<MavenArtifactControllerService>;
  let service: MavenService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'getRepoPermissions',
      'updateRepoSettings',
      'getPathContent',
      'createDownloadToken',
    ]);
    mavenApi = jasmine.createSpyObj<MavenArtifactControllerService>('MavenArtifactControllerService', [
      'listMavenGroups',
      'listMavenArtifacts',
      'listMavenArtifactVersions',
      'getMavenArtifactVersion',
      'deleteGroup',
      'deleteMavenArtifact',
      'deleteMavenArtifactVersion',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: MavenArtifactControllerService, useValue: mavenApi },
      ],
    });
    service = TestBed.inject(MavenService);
  });

  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getRepoPermissions,
    probe: (s) => s.getPathContent(PATH),
    probeApi: () => repoApi.getPathContent,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getRepoPermissions, REPO));

    const paged: PagedCase<MavenService>[] = [
      {
        name: 'searchGroups',
        invoke: (s, group) => s.searchGroups(group, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => mavenApi.listMavenGroups,
        args: (group) => [REPO, group, ...PAGE_ARGS],
      },
      {
        name: 'searchArtifacts',
        invoke: (s, artifact) => s.searchArtifacts(GROUP, artifact, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => mavenApi.listMavenArtifacts,
        args: (artifact) => [GROUP, REPO, artifact, ...PAGE_ARGS],
      },
      {
        name: 'searchArtifactVersions',
        invoke: (s, version) => s.searchArtifactVersions(GROUP, ARTIFACT, version, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => mavenApi.listMavenArtifactVersions,
        args: (version) => [GROUP, ARTIFACT, REPO, version, ...PAGE_ARGS],
      },
    ];
    describePagedCalls(() => service, paged);

    const files = [{ name: 'widget-1.2.3.jar', path: `${PATH}/1.2.3` }];
    const info = { version: VERSION };
    const deleted = { deletedVersionCount: 3 };
    const settings = { versionPolicy: 'RELEASE' } as unknown as RepoSettingsForm;
    const calls: CallCase<MavenService>[] = [
      {
        name: 'updateRepoSettings',
        invoke: (s) => s.updateRepoSettings(settings),
        api: () => repoApi.updateRepoSettings,
        args: [REPO, settings],
        response: restResponse('ignored'),
        expected: undefined,
      },
      {
        name: 'getPathContent',
        invoke: (s) => s.getPathContent(PATH),
        api: () => repoApi.getPathContent,
        args: [PATH, REPO],
        response: restResponse(files),
        expected: files,
      },
      {
        name: 'createDownloadToken',
        invoke: (s) => s.createDownloadToken(PATH),
        api: () => repoApi.createDownloadToken,
        args: [PATH, REPO],
        response: restResponse('token-1'),
        expected: 'token-1',
      },
      {
        name: 'fetchArtifactVersion',
        invoke: (s) => s.fetchArtifactVersion(GROUP, ARTIFACT, VERSION),
        api: () => mavenApi.getMavenArtifactVersion,
        args: [GROUP, ARTIFACT, VERSION, REPO],
        response: restResponse(info),
        expected: info,
      },
      {
        name: 'deleteGroup',
        invoke: (s) => s.deleteGroup(GROUP),
        api: () => mavenApi.deleteGroup,
        args: [GROUP, REPO],
        response: restResponse(deleted),
        expected: deleted,
        notCalled: () => [mavenApi.deleteMavenArtifact, mavenApi.deleteMavenArtifactVersion],
      },
      {
        name: 'deleteArtifact',
        invoke: (s) => s.deleteArtifact(GROUP, ARTIFACT),
        api: () => mavenApi.deleteMavenArtifact,
        args: [GROUP, ARTIFACT, REPO],
        response: restResponse(deleted),
        expected: deleted,
        notCalled: () => [mavenApi.deleteGroup, mavenApi.deleteMavenArtifactVersion],
      },
      {
        name: 'deleteVersion',
        invoke: (s) => s.deleteVersion(GROUP, ARTIFACT, VERSION),
        api: () => mavenApi.deleteMavenArtifactVersion,
        args: [GROUP, ARTIFACT, VERSION, REPO],
        response: restResponse(deleted),
        expected: deleted,
        notCalled: () => [mavenApi.deleteGroup, mavenApi.deleteMavenArtifact],
      },
    ];
    describeCalls(() => service, calls);
  });
});
