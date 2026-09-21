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

import { describeRouteTree } from '../../../../shared/testing/route-spec-helpers';
import { RepositorySettingsComponent } from '../repo-settings/repository-settings.component';
import { DockerComponent } from './docker.component';
import { DOCKER_ROUTES } from './docker.routes';
import { DockerImagesListComponent } from './images/list/docker-images-list.component';
import { DockerImagesManifestListComponent } from './images/manifest-list/docker-images-manifest-list.component';
import { DockerImagesTagDetailComponent } from './images/tag-detail/docker-images-tag-detail.component';
import { DockerImagesTagListComponent } from './images/tag-list/docker-images-tag-list.component';

describe('DOCKER_ROUTES', () => {
  describeRouteTree(
    () => DOCKER_ROUTES,
    [
      { path: '', component: DockerComponent },
      { path: '', component: DockerImagesListComponent, title: 'Docker Images | Repsy', full: true },
      {
        path: 'settings',
        component: RepositorySettingsComponent,
        title: 'repsy | Docker Repository Settings',
        full: true,
      },
      { path: ':image' },
      { path: ':image', component: DockerImagesTagListComponent, title: 'repsy | Docker Image Tags', full: true },
      { path: ':image/:tag' },
      {
        path: ':image/:tag',
        component: DockerImagesManifestListComponent,
        title: 'repsy | Docker Manifests',
        full: true,
      },
      {
        path: ':image/:tag/detail',
        component: DockerImagesTagDetailComponent,
        title: 'repsy | Docker Tag Detail',
        full: true,
      },
    ],
  );
});
