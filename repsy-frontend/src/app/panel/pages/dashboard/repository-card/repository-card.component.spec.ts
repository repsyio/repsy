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

import { Router } from '@angular/router';

import { RepositoryCardComponent } from './repository-card.component';

describe('RepositoryCardComponent', () => {
  let router: jasmine.SpyObj<Router>;
  let component: RepositoryCardComponent;

  beforeEach(() => {
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    component = new RepositoryCardComponent(router);
  });

  // RPS-1668: a `type` query param, not router state, so a reload of the repository list keeps the
  // dashboard's pre-selected type too.
  const cases: [name: string, invoke: () => void, type: string][] = [
    ['routeMaven', () => component.routeMaven(), 'maven'],
    ['routeNpm', () => component.routeNpm(), 'npm'],
    ['routePypi', () => component.routePypi(), 'pypi'],
    ['routeDocker', () => component.routeDocker(), 'docker'],
    ['routeCargo', () => component.routeCargo(), 'cargo'],
    ['routeGolang', () => component.routeGolang(), 'golang'],
    ['routeHelm', () => component.routeHelm(), 'helm'],
    ['routeNuget', () => component.routeNuget(), 'nuget'],
    ['routeRuby', () => component.routeRuby(), 'ruby'],
  ];

  for (const [name, invoke, type] of cases) {
    it(`${name} navigates to /repositories with ?type=${type}`, () => {
      invoke();

      expect(router.navigate).toHaveBeenCalledOnceWith(['/repositories'], { queryParams: { type } });
    });
  }
});
