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
import { of, throwError } from 'rxjs';

import {
  MavenArtifactControllerService,
  MavenGroupControllerService,
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
  let groupApi: jasmine.SpyObj<MavenGroupControllerService>;
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
      'deleteMavenGroup',
      'deleteMavenArtifact',
      'deleteMavenArtifactVersion',
    ]);
    groupApi = jasmine.createSpyObj<MavenGroupControllerService>('MavenGroupControllerService', [
      'getMavenGroupSummary',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: MavenArtifactControllerService, useValue: mavenApi },
        { provide: MavenGroupControllerService, useValue: groupApi },
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
    const summary = { groupName: GROUP, artifactCount: 2, versionCount: 5 };
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
        name: 'getGroupSummary',
        invoke: (s) => s.getGroupSummary(GROUP),
        api: () => groupApi.getMavenGroupSummary,
        args: [GROUP, REPO],
        response: restResponse(summary),
        expected: summary,
        notCalled: () => [mavenApi.deleteMavenGroup],
      },
      {
        name: 'deleteGroup',
        invoke: (s) => s.deleteGroup(GROUP),
        api: () => mavenApi.deleteMavenGroup,
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
        notCalled: () => [mavenApi.deleteMavenGroup, mavenApi.deleteMavenArtifactVersion],
      },
      {
        name: 'deleteVersion',
        invoke: (s) => s.deleteVersion(GROUP, ARTIFACT, VERSION),
        api: () => mavenApi.deleteMavenArtifactVersion,
        args: [GROUP, ARTIFACT, VERSION, REPO],
        response: restResponse(deleted),
        expected: deleted,
        notCalled: () => [mavenApi.deleteMavenGroup, mavenApi.deleteMavenArtifact],
      },
    ];
    describeCalls(() => service, calls);

    // RPS-1348: the extra sentence of the delete-version confirmation, from the versions probe and the summary.
    describe('getVersionDeleteWarning', () => {
      const versionsPage = (count: number) =>
        restResponse({ content: Array.from({ length: count }, (_, i) => ({ versionName: `${i}.0` })), page: {} });

      function warning(): string | null | undefined {
        let result: string | null | undefined;
        service.getVersionDeleteWarning(GROUP, ARTIFACT).subscribe((w) => (result = w));
        return result;
      }

      it('warns that the artifact and the group go too when it is the last version of the only artifact', () => {
        mavenApi.listMavenArtifactVersions.and.returnValue(of(versionsPage(1)) as never);
        groupApi.getMavenGroupSummary.and.returnValue(
          of(restResponse({ groupName: GROUP, artifactCount: 1, versionCount: 1 })) as never,
        );

        expect(warning()).toBe(
          'This is the only version of widget and widget is the only artifact of the group io.acme, ' +
            'so the artifact and the group are removed too. This cannot be undone.',
        );
        expect(groupApi.getMavenGroupSummary).toHaveBeenCalledOnceWith(GROUP, REPO);
      });

      it('probes only the first two versions, of the artifact, unfiltered', () => {
        mavenApi.listMavenArtifactVersions.and.returnValue(of(versionsPage(2)) as never);

        warning();

        expect(mavenApi.listMavenArtifactVersions).toHaveBeenCalledOnceWith(GROUP, ARTIFACT, REPO, undefined, 0, 2, [
          'versionName,DESC',
        ]);
      });

      it('asks for nothing more, and does not read the group, when the artifact has other versions', () => {
        mavenApi.listMavenArtifactVersions.and.returnValue(of(versionsPage(2)) as never);

        expect(warning()).toBeNull();
        expect(groupApi.getMavenGroupSummary).not.toHaveBeenCalled();
      });

      it('asks for nothing more when the group has other artifacts', () => {
        mavenApi.listMavenArtifactVersions.and.returnValue(of(versionsPage(1)) as never);
        groupApi.getMavenGroupSummary.and.returnValue(
          of(restResponse({ groupName: GROUP, artifactCount: 2, versionCount: 3 })) as never,
        );

        expect(warning()).toBeNull();
      });

      it('falls back to the plain confirmation when the probe fails', () => {
        mavenApi.listMavenArtifactVersions.and.returnValue(throwError(() => new Error('boom')) as never);

        expect(warning()).toBeNull();
      });

      it('falls back to the plain confirmation when the summary fails', () => {
        mavenApi.listMavenArtifactVersions.and.returnValue(of(versionsPage(1)) as never);
        groupApi.getMavenGroupSummary.and.returnValue(throwError(() => new Error('boom')) as never);

        expect(warning()).toBeNull();
      });
    });
  });
});
