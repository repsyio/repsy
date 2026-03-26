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
import { MavenArtifactsGroupListComponent } from './artifacts/group/maven-artifacts-group-list.component';
import { MavenArtifactsListComponent } from './artifacts/list/maven-artifacts-list.component';
import { MavenArtifactsVersionDetailComponent } from './artifacts/version-detail/maven-artifacts-version-detail.component';
import { MavenArtifactsVersionListComponent } from './artifacts/version-list/maven-artifacts-version-list.component';
import { MavenBrowserComponent } from './browser/maven-browser.component';
import { MavenComponent } from './maven.component';

export const MAVEN_ROUTES: Routes = [
  {
    path: '',
    component: MavenComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: MavenArtifactsGroupListComponent,
        title: 'repsy | Maven Groups',
      },
      {
        path: 'settings',
        pathMatch: 'full',
        component: RepositorySettingsComponent,
        title: 'repsy | Repository Settings',
      },
      {
        path: 'browser',
        pathMatch: 'full',
        component: MavenBrowserComponent,
        title: 'repsy | Browser',
      },
      {
        path: ':group',
        children: [
          {
            path: '',
            pathMatch: 'full',
            component: MavenArtifactsListComponent,
            title: 'repsy | Maven Artifacts',
          },
          {
            path: ':artifact',
            children: [
              {
                path: '',
                pathMatch: 'full',
                component: MavenArtifactsVersionListComponent,
                title: 'repsy | Maven Artifact Versions',
              },
              {
                path: ':version',
                pathMatch: 'full',
                component: MavenArtifactsVersionDetailComponent,
                title: 'repsy | Maven Version Detail',
              },
            ],
          },
        ],
      },
    ],
  },
];
