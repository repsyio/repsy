import { Routes } from '@angular/router';

import { RepositorySettingsComponent } from '../repo-settings/repository-settings.component';
import { CargoComponent } from './cargo.component';
import { CargoCratesListComponent } from './crates/list/cargo-crates-list.component';
import { CargoCratesVersionDetailComponent } from './crates/version-detail/cargo-crates-version-detail.component';
import { CargoCratesVersionListComponent } from './crates/version-list/cargo-crates-version-list.component';

export const CARGO_ROUTES: Routes = [
  {
    path: '',
    component: CargoComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: CargoCratesListComponent,
        title: 'repsy | Cargo Crates',
      },
      {
        path: 'settings',
        pathMatch: 'full',
        component: RepositorySettingsComponent,
        title: 'repsy | Cargo Repository Settings',
      },
      {
        path: ':crate',
        children: [
          {
            path: '',
            pathMatch: 'full',
            component: CargoCratesVersionListComponent,
            title: 'repsy | Cargo Crate Versions',
          },
          {
            path: ':version',
            pathMatch: 'full',
            component: CargoCratesVersionDetailComponent,
            title: 'repsy | Cargo Crate Version Detail',
          },
        ],
      },
    ],
  },
];
