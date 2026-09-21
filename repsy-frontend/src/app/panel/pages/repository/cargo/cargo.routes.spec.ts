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
import { CargoComponent } from './cargo.component';
import { CARGO_ROUTES } from './cargo.routes';
import { CargoCratesListComponent } from './crates/list/cargo-crates-list.component';
import { CargoCratesVersionDetailComponent } from './crates/version-detail/cargo-crates-version-detail.component';
import { CargoCratesVersionListComponent } from './crates/version-list/cargo-crates-version-list.component';

describe('CARGO_ROUTES', () => {
  describeRouteTree(
    () => CARGO_ROUTES,
    [
      { path: '', component: CargoComponent },
      { path: '', component: CargoCratesListComponent, title: 'repsy | Cargo Crates', full: true },
      {
        path: 'settings',
        component: RepositorySettingsComponent,
        title: 'repsy | Cargo Repository Settings',
        full: true,
      },
      { path: ':crate' },
      { path: ':crate', component: CargoCratesVersionListComponent, title: 'repsy | Cargo Crate Versions', full: true },
      {
        path: ':crate/:version',
        component: CargoCratesVersionDetailComponent,
        title: 'repsy | Cargo Crate Version Detail',
        full: true,
      },
    ],
  );
});
