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
import { NpmComponent } from './npm.component';
import { NpmPackagesListComponent } from './packages/list/npm-packages-list.component';
import { NpmPackagesScopeFilterComponent } from './packages/scoped-list/npm-packages-scoped-list.component';
import { NpmPackagesVersionDetailComponent } from './packages/version-detail/npm-packages-version-detail.component';
import { NpmPackagesVersionListComponent } from './packages/version-list/npm-packages-version-list.component';

export const NPM_ROUTES: Routes = [
  {
    path: '',
    component: NpmComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: NpmPackagesListComponent,
        title: 'repsy | Npm Packages',
      },
      {
        path: 'settings',
        pathMatch: 'full',
        component: RepositorySettingsComponent,
        title: 'repsy | Npm Repository Settings',
      },
      {
        path: ':scope',
        children: [
          {
            path: '',
            pathMatch: 'full',
            component: NpmPackagesScopeFilterComponent,
            title: 'repsy | Npm Package Scopes',
          },
          {
            path: ':package',
            children: [
              {
                path: '',
                pathMatch: 'full',
                component: NpmPackagesVersionListComponent,
                title: 'Npm Package Versions | Repsy',
              },
              {
                path: ':version',
                pathMatch: 'full',
                component: NpmPackagesVersionDetailComponent,
                title: 'repsy | Npm Version Details',
              },
            ],
          },
        ],
      },
    ],
  },
];
