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
import { HelmChartsListComponent } from './charts/list/helm-charts-list.component';
import { HelmChartsVersionDetailComponent } from './charts/version-detail/helm-charts-version-detail.component';
import { HelmChartsVersionListComponent } from './charts/version-list/helm-charts-version-list.component';
import { HelmComponent } from './helm.component';
import { HELM_ROUTES } from './helm.routes';

describe('HELM_ROUTES', () => {
  describeRouteTree(
    () => HELM_ROUTES,
    [
      { path: '', component: HelmComponent },
      { path: '', component: HelmChartsListComponent, title: 'repsy | Helm Charts', full: true },
      {
        path: 'settings',
        component: RepositorySettingsComponent,
        title: 'repsy | Helm Repository Settings',
        full: true,
      },
      { path: ':name' },
      { path: ':name', component: HelmChartsVersionListComponent, title: 'repsy | Helm Chart Versions', full: true },
      {
        path: ':name/:version',
        component: HelmChartsVersionDetailComponent,
        title: 'repsy | Helm Chart Version Detail',
        full: true,
      },
    ],
  );
});
