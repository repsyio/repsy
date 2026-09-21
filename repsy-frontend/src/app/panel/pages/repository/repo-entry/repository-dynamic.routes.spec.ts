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
import { TestBed } from '@angular/core/testing';
import { CanMatchFn, DefaultUrlSerializer, Route, Router, Routes, UrlTree } from '@angular/router';
import { firstValueFrom, isObservable, Observable, of, throwError } from 'rxjs';

import { CargoComponent } from '../cargo/cargo.component';
import { CARGO_ROUTES } from '../cargo/cargo.routes';
import { DockerComponent } from '../docker/docker.component';
import { DOCKER_ROUTES } from '../docker/docker.routes';
import { GolangComponent } from '../golang/golang.component';
import { GOLANG_ROUTES } from '../golang/golang.routes';
import { HelmComponent } from '../helm/helm.component';
import { HELM_ROUTES } from '../helm/helm.routes';
import { MavenComponent } from '../maven/maven.component';
import { MAVEN_ROUTES } from '../maven/maven.routes';
import { NpmComponent } from '../npm/npm.component';
import { NPM_ROUTES } from '../npm/npm.routes';
import { NugetComponent } from '../nuget/nuget.component';
import { NUGET_ROUTES } from '../nuget/nuget.routes';
import { PypiComponent } from '../pypi/pypi.component';
import { PYPI_ROUTES } from '../pypi/pypi.routes';
import { RubyComponent } from '../ruby/ruby.component';
import { RUBY_ROUTES } from '../ruby/ruby.routes';
import { RepoLookupService, RepoType } from './repo-lookup.service';
import { REPOSITORY_DYNAMIC_ROUTES } from './repository-dynamic.routes';

/** The nine entries in the order they must be declared, with the route set each one lazy-loads. */
const ENTRIES: { type: RepoType; routes: Routes; component: Type<unknown> }[] = [
  { type: 'maven', routes: MAVEN_ROUTES, component: MavenComponent },
  { type: 'npm', routes: NPM_ROUTES, component: NpmComponent },
  { type: 'pypi', routes: PYPI_ROUTES, component: PypiComponent },
  { type: 'docker', routes: DOCKER_ROUTES, component: DockerComponent },
  { type: 'cargo', routes: CARGO_ROUTES, component: CargoComponent },
  { type: 'golang', routes: GOLANG_ROUTES, component: GolangComponent },
  { type: 'helm', routes: HELM_ROUTES, component: HelmComponent },
  { type: 'nuget', routes: NUGET_ROUTES, component: NugetComponent },
  { type: 'ruby', routes: RUBY_ROUTES, component: RubyComponent },
];

describe('REPOSITORY_DYNAMIC_ROUTES', () => {
  const urlSerializer = new DefaultUrlSerializer();
  let navigationUrl: UrlTree | null | undefined;
  let checkRepoType: jasmine.Spy;

  /** Sets the URL the router pretends to be navigating to; `undefined` stands for "no navigation in progress". */
  function navigateTo(url: string | undefined): void {
    navigationUrl = url === undefined ? undefined : urlSerializer.parse(url);
  }

  /** Runs the `canMatch` guard of the entry at `index` in an injection context, as the router does. */
  async function canMatch(index: number): Promise<boolean> {
    const route = REPOSITORY_DYNAMIC_ROUTES[index];
    const guard = route.canMatch![0] as CanMatchFn;
    const result = TestBed.runInInjectionContext(() => guard(route, []));
    return isObservable(result) ? firstValueFrom(result as Observable<boolean>) : (result as boolean);
  }

  beforeEach(() => {
    navigateTo('/acme-repo');
    checkRepoType = jasmine.createSpy('checkRepoType').and.returnValue(of('maven'));
    TestBed.configureTestingModule({
      providers: [
        {
          provide: Router,
          useValue: {
            getCurrentNavigation: () => (navigationUrl === undefined ? null : { extractedUrl: navigationUrl }),
          },
        },
        { provide: RepoLookupService, useValue: { checkRepoType } },
      ],
    });
  });

  it('declares one entry per repository type, in this order', () => {
    expect(REPOSITORY_DYNAMIC_ROUTES.length).toBe(ENTRIES.length);
    REPOSITORY_DYNAMIC_ROUTES.forEach((route: Route) => {
      expect(route.path).toBe('');
      expect(route.canMatch?.length).toBe(1);
      expect(route.loadChildren).toEqual(jasmine.any(Function));
      expect(route.component).toBeUndefined();
    });
  });

  ENTRIES.forEach(({ type, routes, component }, index) => {
    describe(`the ${type} entry`, () => {
      it('lazy-loads the routes of its protocol', async () => {
        const loaded = await (REPOSITORY_DYNAMIC_ROUTES[index].loadChildren as () => Promise<Routes>)();

        expect(loaded).toBe(routes);
        expect(loaded[0].component).toBe(component);
      });

      it('matches only a repository of type ' + type, async () => {
        for (const candidate of ENTRIES) {
          checkRepoType.and.returnValue(of(candidate.type));

          expect(await canMatch(index))
            .withContext(`repository of type ${candidate.type}`)
            .toBe(candidate.type === type);
        }
      });
    });
  });

  describe('canMatch', () => {
    it('looks up the type of the repository named by the first URL segment', async () => {
      navigateTo('/acme-repo/settings');

      expect(await canMatch(0)).toBeTrue();

      expect(checkRepoType).toHaveBeenCalledOnceWith('acme-repo');
    });

    it('ignores the query string and the fragment', async () => {
      navigateTo('/acme-repo?tab=1#top');

      await canMatch(0);

      expect(checkRepoType).toHaveBeenCalledOnceWith('acme-repo');
    });

    it('passes the decoded segment to the lookup', async () => {
      navigateTo('/acme%20repo');

      await canMatch(0);

      expect(checkRepoType).toHaveBeenCalledOnceWith('acme repo');
    });

    it('lets only the first answer of the lookup decide', async () => {
      checkRepoType.and.returnValue(of('npm', 'maven'));

      expect(await canMatch(0)).toBeFalse();
      expect(await canMatch(1)).toBeTrue();
    });

    it('does not match, and does not look anything up, when no navigation is in progress', async () => {
      navigateTo(undefined);

      expect(await canMatch(0)).toBeFalse();
      expect(checkRepoType).not.toHaveBeenCalled();
    });

    it('does not match a navigation without an extracted URL', async () => {
      navigationUrl = null;

      expect(await canMatch(0)).toBeFalse();
      expect(checkRepoType).not.toHaveBeenCalled();
    });

    it('does not match, and does not look anything up, when the URL has no segments', async () => {
      navigateTo('/');

      expect(await canMatch(0)).toBeFalse();
      expect(checkRepoType).not.toHaveBeenCalled();
    });

    it('does not match a URL that only has a secondary outlet', async () => {
      navigateTo('/(side:acme-repo)');

      expect(await canMatch(0)).toBeFalse();
      expect(checkRepoType).not.toHaveBeenCalled();
    });

    it('does not match when the lookup fails', async () => {
      checkRepoType.and.returnValue(throwError(() => new Error('not found')));

      for (let index = 0; index < ENTRIES.length; index++) {
        expect(await canMatch(index))
          .withContext(`entry ${index}`)
          .toBeFalse();
      }
    });
  });
});
