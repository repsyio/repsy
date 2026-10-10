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

import { HttpContext } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of } from 'rxjs';

import { DockerImagesApi, ImageListItem, ReposApi } from '../../../../../../generated/api';
import { SILENT_ERROR } from '../../../../../shared/interceptors/error-handler.interceptor';
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
  selectRepo,
  SORT,
} from '../../testing/protocol-service-spec-helpers';
import { DockerService } from './docker.service';

// A name with several segments: `-` takes the path segment and the name goes in the `image` query.
const IMAGE = 'team/app';
const IMAGE_PATH = '-';
const TAG = 'v1';
const DIGEST = 'sha256:abc';

describe('DockerService', () => {
  let repoApi: jasmine.SpyObj<ReposApi>;
  let dockerApi: jasmine.SpyObj<DockerImagesApi>;
  let service: DockerService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ReposApi>('ReposApi', ['getRepoPermissions']);
    dockerApi = jasmine.createSpyObj<DockerImagesApi>('DockerImagesApi', [
      'listDockerImages',
      'listDockerImageTags',
      'listDockerTagManifests',
      'deleteDockerImage',
      'getDockerImage',
      'getDockerImageTag',
      'deleteDockerTag',
      'getDockerImageManifest',
      'getDockerImageConfig',
    ]);
    TestBed.configureTestingModule({
      providers: [
        { provide: ReposApi, useValue: repoApi },
        { provide: DockerImagesApi, useValue: dockerApi },
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
        bare: true,
      },
      {
        name: 'searchTags',
        invoke: (s, name) => s.searchTags(name, SORT, IMAGE, PAGE_INDEX, PAGE_SIZE),
        api: () => dockerApi.listDockerImageTags,
        args: (name) => [IMAGE_PATH, REPO, IMAGE, name, ...PAGE_ARGS],
        bare: true,
      },
      {
        name: 'searchManifests',
        invoke: (s, name) => s.searchManifests(name, SORT, IMAGE, TAG, PAGE_INDEX, PAGE_SIZE),
        api: () => dockerApi.listDockerTagManifests,
        args: (name) => [IMAGE_PATH, TAG, REPO, IMAGE, name, ...PAGE_ARGS],
        bare: true,
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
        args: [IMAGE_PATH, REPO, IMAGE],
        response: undefined,
        expected: undefined,
      },
      {
        name: 'fetchTag',
        invoke: (s) => s.fetchTag(IMAGE, TAG),
        api: () => dockerApi.getDockerImageTag,
        args: [IMAGE_PATH, TAG, REPO, IMAGE],
        response: tag,
        expected: tag,
      },
      {
        name: 'deleteTag',
        invoke: (s) => s.deleteTag(IMAGE, TAG),
        api: () => dockerApi.deleteDockerTag,
        args: [IMAGE_PATH, TAG, REPO, IMAGE],
        response: undefined,
        expected: undefined,
      },
      {
        name: 'fetchManifestText',
        invoke: (s) => s.fetchManifestText(IMAGE, DIGEST),
        api: () => dockerApi.getDockerImageManifest,
        args: [IMAGE_PATH, DIGEST, REPO, IMAGE],
        response: manifestText,
        expected: manifestText,
      },
      {
        name: 'fetchConfigText',
        invoke: (s) => s.fetchConfigText(IMAGE, DIGEST),
        api: () => dockerApi.getDockerImageConfig,
        args: [IMAGE_PATH, DIGEST, REPO, IMAGE],
        response: configText,
        expected: configText,
      },
    ];
    describeCalls(() => service, calls);

    describe('fetchImageSummary', () => {
      const summary: ImageListItem = { name: IMAGE, tagCount: 0, untaggedManifestCount: 2, untaggedSize: 2048 };

      it('reads the image of the active repository', async () => {
        dockerApi.getDockerImage.and.returnValue(of(summary) as never);

        const result = await firstValueFrom(service.fetchImageSummary(IMAGE));

        expect(result).toEqual(summary);
        expect(dockerApi.getDockerImage).toHaveBeenCalledTimes(1);
        const args = dockerApi.getDockerImage.calls.mostRecent().args as unknown[];
        expect(args.slice(0, 5)).toEqual([IMAGE_PATH, REPO, IMAGE, 'body', false]);
        expect(args[5]).toEqual({ context: jasmine.any(HttpContext) });
      });

      it('keeps a single segment name in the path and sends no image query', async () => {
        dockerApi.getDockerImage.and.returnValue(of({ ...summary, name: 'nginx' }) as never);

        await firstValueFrom(service.fetchImageSummary('nginx'));

        const args = dockerApi.getDockerImage.calls.mostRecent().args as unknown[];
        expect(args.slice(0, 3)).toEqual(['nginx', REPO, undefined]);
      });

      it('does not toast a 404, because the page leaves when the image is gone', () => {
        dockerApi.getDockerImage.and.returnValue(of(summary) as never);

        service.fetchImageSummary(IMAGE).subscribe();

        const options = dockerApi.getDockerImage.calls.mostRecent().args[5] as unknown as {
          context: HttpContext;
        };
        expect(options.context.get(SILENT_ERROR)).toBeTrue();
      });
    });
  });
});
