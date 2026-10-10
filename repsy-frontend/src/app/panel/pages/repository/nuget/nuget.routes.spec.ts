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
import { NuGetComponent } from './nuget.component';
import { NUGET_ROUTES } from './nuget.routes';
import { NuGetPackagesListComponent } from './packages/list/nuget-packages-list.component';
import { NuGetPackagesVersionDetailComponent } from './packages/version-detail/nuget-packages-version-detail.component';
import { NuGetPackagesVersionListComponent } from './packages/version-list/nuget-packages-version-list.component';

describe('NUGET_ROUTES', () => {
  describeRouteTree(
    () => NUGET_ROUTES,
    [
      { path: '', component: NuGetComponent },
      { path: '', component: NuGetPackagesListComponent, title: 'Repsy | NuGet Packages', full: true },
      {
        path: 'settings',
        component: RepositorySettingsComponent,
        title: 'Repsy | NuGet Repository Settings',
        full: true,
      },
      { path: ':packageName' },
      {
        path: ':packageName',
        component: NuGetPackagesVersionListComponent,
        title: 'Repsy | NuGet Package Versions',
        full: true,
      },
      {
        path: ':packageName/:version',
        component: NuGetPackagesVersionDetailComponent,
        title: 'Repsy | NuGet Version Detail',
        full: true,
      },
    ],
  );
});
