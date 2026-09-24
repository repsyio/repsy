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

import { Type } from '@angular/core';
import { Route, Routes } from '@angular/router';

import { routes } from './app.routes';
import { AuthRedirectComponent } from './auth/components/redirect/auth-redirect.component';
import { adminGuard } from './auth/guard/admin.guard';
import { AuthGuard } from './auth/guard/auth.guard';
import { AuthRedirectGuard } from './auth/guard/auth-redirect.guard';
import { LoginComponent } from './auth/pages/login/login.component';
import { NotFoundComponent } from './panel/pages/not-found/not-found.component';
import { ProfileComponent } from './panel/pages/profile/profile.component';
import { repoTypeResolver } from './panel/pages/repository/repo-entry/repo-type.resolver';
import { REPOSITORY_DYNAMIC_ROUTES } from './panel/pages/repository/repo-entry/repository-dynamic.routes';
import { RepositoryWrapperComponent } from './panel/pages/repository/repo-entry/repository-wrapper.component';
import { RepositoryComponent } from './panel/pages/repository/repository.component';
import { SecurityComponent } from './panel/pages/security/security.component';
import { UserManagementComponent } from './panel/pages/user/user-management/user-management.component';
import { PanelLayoutComponent } from './panel/shared/layout/panel-layout.component';

describe('app routes', () => {
  const layout = (): Route => routes.find((route) => route.component === PanelLayoutComponent)!;
  const child = (path: string): Route => layout().children!.find((route) => route.path === path)!;

  it('declares the login page, the root redirect component, the panel layout and the wildcard, in this order', () => {
    expect(routes.map((route) => [route.path, route.component])).toEqual([
      ['login', LoginComponent],
      ['', AuthRedirectComponent],
      ['', PanelLayoutComponent],
      ['**', undefined],
    ]);
  });

  it('guards the login page with the redirect guard and matches the root only in full', () => {
    const login = routes[0];

    expect(login.pathMatch).toBe('full');
    expect(login.canActivate).toEqual([AuthRedirectGuard]);
    expect(login.title).toBe('repsy | Login');
    expect(routes[1].pathMatch).toBe('full');
  });

  it('sends any unknown URL to the not-found page, which lives inside the panel layout', () => {
    expect(routes.at(-1)).toEqual(jasmine.objectContaining({ path: '**', redirectTo: 'not-found' }));
    expect(routes.at(-1)!.component).toBeUndefined();
    expect(child('not-found').component).toBe(NotFoundComponent);
  });

  describe('inside the panel layout', () => {
    it('lists the static pages before the :repoName catch-all, which must stay last', () => {
      expect(layout().children!.map((route) => route.path)).toEqual([
        'profile',
        'repositories',
        'users',
        'security',
        'not-found',
        ':repoName',
      ]);
    });

    it('serves the static pages from their components with their titles', () => {
      const pages: [string, Type<unknown>, string][] = [
        ['profile', ProfileComponent, 'repsy | Account'],
        ['repositories', RepositoryComponent, 'repsy | Repositories'],
        ['users', UserManagementComponent, 'repsy | User Management'],
        ['security', SecurityComponent, 'repsy | Security'],
        ['not-found', NotFoundComponent, 'repsy | Not Found'],
      ];

      for (const [path, component, title] of pages) {
        expect(child(path).component).withContext(path).toBe(component);
        expect(child(path).title).withContext(path).toBe(title);
      }
    });

    it('requires a session for the signed-in pages and the administrator role for users and security', () => {
      expect(child('profile').canActivate).toEqual([AuthGuard]);
      expect(child('repositories').canActivate).toEqual([AuthGuard]);
      expect(child('users').canActivate).toEqual([AuthGuard, adminGuard]);
      expect(child('security').canActivate).toEqual([AuthGuard, adminGuard]);
      expect(child('not-found').canActivate).toBeUndefined();
    });

    it('matches the static pages in full', () => {
      for (const path of ['profile', 'repositories', 'users', 'security']) {
        expect(child(path).pathMatch).withContext(path).toBe('full');
      }
    });
  });

  describe(':repoName', () => {
    it('is wrapped in the repository component, behind the session guard, and resolves the repository data first', () => {
      const repo = child(':repoName');

      expect(repo.component).toBe(RepositoryWrapperComponent);
      expect(repo.canActivate).toEqual([AuthGuard]);
      expect(repo.resolve).toEqual({ repoData: repoTypeResolver });
    });

    it('has one child that lazy-loads the dynamic repository routes', async () => {
      const children = child(':repoName').children!;

      expect(children.map((route) => route.path)).toEqual(['']);
      expect(await (children[0].loadChildren as () => Promise<Routes>)()).toBe(REPOSITORY_DYNAMIC_ROUTES);
    });
  });
});
