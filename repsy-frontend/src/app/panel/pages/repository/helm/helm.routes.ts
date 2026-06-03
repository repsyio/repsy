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
import { HelmChartsListComponent } from './charts/list/helm-charts-list.component';
import { HelmChartsVersionDetailComponent } from './charts/version-detail/helm-charts-version-detail.component';
import { HelmChartsVersionListComponent } from './charts/version-list/helm-charts-version-list.component';
import { HelmComponent } from './helm.component';

export const HELM_ROUTES: Routes = [
  {
    path: '',
    component: HelmComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: HelmChartsListComponent,
        title: 'repsy | Helm Charts',
      },
      {
        path: 'settings',
        pathMatch: 'full',
        component: RepositorySettingsComponent,
        title: 'repsy | Helm Repository Settings',
      },
      {
        path: ':name',
        children: [
          {
            path: '',
            pathMatch: 'full',
            component: HelmChartsVersionListComponent,
            title: 'repsy | Helm Chart Versions',
          },
          {
            path: ':version',
            pathMatch: 'full',
            component: HelmChartsVersionDetailComponent,
            title: 'repsy | Helm Chart Version Detail',
          },
        ],
      },
    ],
  },
];
