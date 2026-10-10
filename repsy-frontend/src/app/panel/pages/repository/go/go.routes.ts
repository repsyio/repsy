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
import { GoComponent } from './go.component';
import { GoModulesListComponent } from './modules/list/go-modules-list.component';
import { GoModuleVersionDetailComponent } from './modules/version-detail/go-module-version-detail.component';
import { GoModuleVersionListComponent } from './modules/version-list/go-module-version-list.component';

export const GO_ROUTES: Routes = [
  {
    path: '',
    component: GoComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: GoModulesListComponent,
        title: 'repsy | Go Modules',
      },
      {
        path: 'modules',
        children: [
          {
            path: '',
            pathMatch: 'full',
            component: GoModuleVersionListComponent,
            title: 'repsy | Go Module Versions',
          },
          {
            path: 'version',
            pathMatch: 'full',
            component: GoModuleVersionDetailComponent,
            title: 'repsy | Go Module Version Detail',
          },
        ],
      },
      {
        path: 'settings',
        pathMatch: 'full',
        component: RepositorySettingsComponent,
        title: 'repsy | Go Repository Settings',
      },
    ],
  },
];
