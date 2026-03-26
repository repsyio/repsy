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
///

import { Routes } from '@angular/router';

import { RepositorySettingsComponent } from '../repo-settings/repository-settings.component';
import { DockerComponent } from './docker.component';
import { DockerImagesListComponent } from './images/list/docker-images-list.component';
import { DockerImagesManifestListComponent } from './images/manifest-list/docker-images-manifest-list.component';
import { DockerImagesTagDetailComponent } from './images/tag-detail/docker-images-tag-detail.component';
import { DockerImagesTagListComponent } from './images/tag-list/docker-images-tag-list.component';

export const DOCKER_ROUTES: Routes = [
  {
    path: '',
    component: DockerComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: DockerImagesListComponent,
        title: 'Docker Images | Repsy',
      },
      {
        path: 'settings',
        pathMatch: 'full',
        component: RepositorySettingsComponent,
        title: 'repsy | Docker Repository Settings',
      },
      {
        path: ':image',
        children: [
          {
            path: '',
            pathMatch: 'full',
            component: DockerImagesTagListComponent,
            title: 'repsy | Docker Image Tags',
          },
          {
            path: ':tag',
            children: [
              {
                path: '',
                pathMatch: 'full',
                component: DockerImagesManifestListComponent,
                title: 'repsy | Docker Manifests',
              },
              {
                path: 'detail',
                pathMatch: 'full',
                component: DockerImagesTagDetailComponent,
                title: 'repsy | Docker Tag Detail',
              },
            ],
          },
        ],
      },
    ],
  },
];
