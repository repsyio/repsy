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
import { GolangComponent } from './golang.component';
import { GolangModulesListComponent } from './modules/list/golang-modules-list.component';
import { GolangModuleVersionDetailComponent } from './modules/version-detail/golang-module-version-detail.component';
import { GolangModuleVersionListComponent } from './modules/version-list/golang-module-version-list.component';

export const GOLANG_ROUTES: Routes = [
  {
    path: '',
    component: GolangComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: GolangModulesListComponent,
        title: 'repsy | Golang Modules',
      },
      {
        path: 'modules',
        children: [
          {
            path: '',
            pathMatch: 'full',
            component: GolangModuleVersionListComponent,
            title: 'repsy | Golang Module Versions',
          },
          {
            path: 'version',
            pathMatch: 'full',
            component: GolangModuleVersionDetailComponent,
            title: 'repsy | Golang Module Version Detail',
          },
        ],
      },
      {
        path: 'settings',
        pathMatch: 'full',
        component: RepositorySettingsComponent,
        title: 'repsy | Golang Repository Settings',
      },
    ],
  },
];
