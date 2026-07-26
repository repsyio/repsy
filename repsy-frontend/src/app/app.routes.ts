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

import { AuthRedirectComponent } from './auth/components/redirect/auth-redirect.component';
import { adminGuard } from './auth/guard/admin.guard';
import { AuthGuard } from './auth/guard/auth.guard';
import { AuthRedirectGuard } from './auth/guard/auth-redirect.guard';
import { LoginComponent } from './auth/pages/login/login.component';
import { SsoComponent } from './auth/pages/sso/sso.component';
import { NotFoundComponent } from './panel/pages/not-found/not-found.component';
import { repoTypeResolver } from './panel/pages/repository/repo-entry/repo-type.resolver';
import { RepositoryWrapperComponent } from './panel/pages/repository/repo-entry/repository-wrapper.component';
import { RepositoryComponent } from './panel/pages/repository/repository.component';
import { SecurityComponent } from './panel/pages/security/security.component';
import { UserManagementComponent } from './panel/pages/user/user-management/user-management.component';
import { PanelLayoutComponent } from './panel/shared/layout/panel-layout.component';

export const routes: Routes = [
  {
    path: 'admin/login',
    pathMatch: 'full',
    canActivate: [AuthRedirectGuard],
    title: 'repsy | Admin Login',
    component: LoginComponent,
  },
  {
    path: 'sso',
    pathMatch: 'full',
    component: SsoComponent,
  },
  {
    path: '',
    pathMatch: 'full',
    component: AuthRedirectComponent,
  },
  {
    path: '',
    component: PanelLayoutComponent,
    children: [
      {
        path: 'repositories',
        pathMatch: 'full',
        canActivate: [AuthGuard],
        title: 'repsy | Repositories',
        component: RepositoryComponent,
      },
      {
        path: 'users',
        pathMatch: 'full',
        canActivate: [AuthGuard, adminGuard],
        title: 'repsy | User Management',
        component: UserManagementComponent,
      },
      {
        path: 'security',
        pathMatch: 'full',
        canActivate: [AuthGuard, adminGuard],
        title: 'repsy | Security',
        component: SecurityComponent,
      },
      {
        path: 'not-found',
        component: NotFoundComponent,
        title: 'repsy | Not Found',
      },

      {
        path: ':repoName',
        component: RepositoryWrapperComponent,
        canActivate: [AuthGuard],
        resolve: {
          repoData: repoTypeResolver,
        },
        children: [
          {
            path: '',
            loadChildren: () =>
              import('./panel/pages/repository/repo-entry/repository-dynamic.routes').then(
                (m) => m.REPOSITORY_DYNAMIC_ROUTES,
              ),
          },
        ],
      },
    ],
  },

  {
    path: '**',
    component: NotFoundComponent,
    title: 'repsy | Not Found',
  },
];
