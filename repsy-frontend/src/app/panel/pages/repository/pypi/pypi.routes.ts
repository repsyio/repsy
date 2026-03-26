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
import { PypiPackagesListComponent } from './packages/list/pypi-packages-list.component';
import { PypiPackagesVersionDetailComponent } from './packages/version-detail/pypi-packages-version-detail.component';
import { PypiPackagesVersionListComponent } from './packages/version-list/pypi-packages-version-list.component';
import { PypiComponent } from './pypi.component';

export const PYPI_ROUTES: Routes = [
  {
    path: '',
    component: PypiComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: PypiPackagesListComponent,
        title: 'repsy | Pypi Packages',
      },
      {
        path: 'settings',
        pathMatch: 'full',
        component: RepositorySettingsComponent,
        title: 'repsy | Pypi Repository Settings',
      },
      {
        path: ':package',
        children: [
          {
            path: '',
            pathMatch: 'full',
            component: PypiPackagesVersionListComponent,
            title: 'repsy | Pypi Package Versions',
          },
          {
            path: ':version',
            pathMatch: 'full',
            component: PypiPackagesVersionDetailComponent,
            title: 'repsy | Pypi Version Details',
          },
        ],
      },
    ],
  },
];
