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

import { DockerImageControllerService, ProtocolRepoControllerService } from '../../../../../../generated/api';
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
import { DockerService } from './docker.service';

const IMAGE = 'team/app';
const TAG = 'v1';
const DIGEST = 'sha256:abc';

describe('DockerService', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let dockerApi: jasmine.SpyObj<DockerImageControllerService>;
  let service: DockerService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'getRepoPermissions',
    ]);
    dockerApi = jasmine.createSpyObj<DockerImageControllerService>('DockerImageControllerService', [
      'listDockerImages',
      'listDockerImageTags',
      'listTagManifests',
      'deleteDockerImage',
      'getDockerImageTag',
      'deleteTag',
      'getManifest',
      'getConfig',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProtocolRepoControllerService, useValue: repoApi },
        { provide: DockerImageControllerService, useValue: dockerApi },
      ],
    });
    service = TestBed.inject(DockerService);
  });

  describeRepoSelection({
    service: () => service,
    getPermission: () => repoApi.getRepoPermissions,
    probe: (s) => s.deleteImage(IMAGE),
    probeApi: () => dockerApi.deleteDockerImage,
    probeRepoArg: 1,
    resetsOnChange: true,
  });

  describe('with a selected repository', () => {
    beforeEach(() => selectRepo(service, repoApi.getRepoPermissions, REPO));

    // The service signatures put the search term first (or in the middle) while the client wants the image and tag
    // names first, so the argument order is worth pinning.
    const paged: PagedCase<DockerService>[] = [
      {
        name: 'searchImages',
        invoke: (s, name) => s.searchImages(name, SORT, PAGE_INDEX, PAGE_SIZE),
        api: () => dockerApi.listDockerImages,
        args: (name) => [REPO, name, ...PAGE_ARGS],
      },
      {
        name: 'searchTags',
        invoke: (s, name) => s.searchTags(name, SORT, IMAGE, PAGE_INDEX, PAGE_SIZE),
        api: () => dockerApi.listDockerImageTags,
        args: (name) => [IMAGE, REPO, name, ...PAGE_ARGS],
      },
      {
        name: 'searchManifests',
        invoke: (s, name) => s.searchManifests(name, SORT, IMAGE, TAG, PAGE_INDEX, PAGE_SIZE),
        api: () => dockerApi.listTagManifests,
        args: (name) => [IMAGE, TAG, REPO, name, ...PAGE_ARGS],
      },
    ];
    describePagedCalls(() => service, paged);

    const tag = { name: TAG };
    const manifestText = '{"schemaVersion":2}';
    const configText = '{"architecture":"amd64"}';
    const calls: CallCase<DockerService>[] = [
      {
        name: 'deleteImage',
        invoke: (s) => s.deleteImage(IMAGE),
        api: () => dockerApi.deleteDockerImage,
        args: [IMAGE, REPO],
        response: restResponse('ignored'),
        expected: undefined,
      },
      {
        name: 'fetchTag',
        invoke: (s) => s.fetchTag(IMAGE, TAG),
        api: () => dockerApi.getDockerImageTag,
        args: [IMAGE, TAG, REPO],
        response: restResponse(tag),
        expected: tag,
      },
      {
        name: 'deleteTag',
        invoke: (s) => s.deleteTag(IMAGE, TAG),
        api: () => dockerApi.deleteTag,
        args: [IMAGE, TAG, REPO],
        response: restResponse('ignored'),
        expected: undefined,
      },
      {
        name: 'fetchManifestText',
        invoke: (s) => s.fetchManifestText(IMAGE, DIGEST),
        api: () => dockerApi.getManifest,
        args: [IMAGE, DIGEST, REPO],
        response: restResponse(manifestText),
        expected: manifestText,
      },
      {
        name: 'fetchConfigText',
        invoke: (s) => s.fetchConfigText(IMAGE, DIGEST),
        api: () => dockerApi.getConfig,
        args: [IMAGE, DIGEST, REPO],
        response: restResponse(configText),
        expected: configText,
      },
    ];
    describeCalls(() => service, calls);
  });
});
