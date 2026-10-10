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
import { GoComponent } from './go.component';
import { GO_ROUTES } from './go.routes';
import { GoModulesListComponent } from './modules/list/go-modules-list.component';
import { GoModuleVersionDetailComponent } from './modules/version-detail/go-module-version-detail.component';
import { GoModuleVersionListComponent } from './modules/version-list/go-module-version-list.component';

describe('GO_ROUTES', () => {
  describeRouteTree(
    () => GO_ROUTES,
    [
      { path: '', component: GoComponent },
      { path: '', component: GoModulesListComponent, title: 'repsy | Go Modules', full: true },
      { path: 'modules' },
      {
        path: 'modules',
        component: GoModuleVersionListComponent,
        title: 'repsy | Go Module Versions',
        full: true,
      },
      {
        path: 'modules/version',
        component: GoModuleVersionDetailComponent,
        title: 'repsy | Go Module Version Detail',
        full: true,
      },
      {
        path: 'settings',
        component: RepositorySettingsComponent,
        title: 'repsy | Go Repository Settings',
        full: true,
      },
    ],
  );
});
