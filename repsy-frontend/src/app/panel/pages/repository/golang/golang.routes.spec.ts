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
import { GolangComponent } from './golang.component';
import { GOLANG_ROUTES } from './golang.routes';
import { GolangModulesListComponent } from './modules/list/golang-modules-list.component';
import { GolangModuleVersionDetailComponent } from './modules/version-detail/golang-module-version-detail.component';
import { GolangModuleVersionListComponent } from './modules/version-list/golang-module-version-list.component';

describe('GOLANG_ROUTES', () => {
  describeRouteTree(
    () => GOLANG_ROUTES,
    [
      { path: '', component: GolangComponent },
      { path: '', component: GolangModulesListComponent, title: 'repsy | Golang Modules', full: true },
      { path: 'modules' },
      {
        path: 'modules',
        component: GolangModuleVersionListComponent,
        title: 'repsy | Golang Module Versions',
        full: true,
      },
      {
        path: 'modules/version',
        component: GolangModuleVersionDetailComponent,
        title: 'repsy | Golang Module Version Detail',
        full: true,
      },
      {
        path: 'settings',
        component: RepositorySettingsComponent,
        title: 'repsy | Golang Repository Settings',
        full: true,
      },
    ],
  );
});
