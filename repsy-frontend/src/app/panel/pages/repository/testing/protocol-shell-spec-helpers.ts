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

// The behaviour every protocol shell component (`<Proto>Component`, the parent of the list and detail pages) shares:
// it loads the repository permissions once per page load. Each shell spec calls `describeProtocolShell`.
//
// It lives next to the specs and only a spec imports it, so it is never part of the application bundle.

import { Component, Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';

import { RepoPermissionInfo } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import { RepositoryBreadcrumbComponent } from '../breadcrumb/repository-breadcrumb.component';
import { RepoContext, RepoLookupService, RepoType } from '../repo-entry/repo-lookup.service';
import { permission } from './protocol-service-spec-helpers';

@Component({ selector: 'app-repository-breadcrumb', standalone: true, template: '' })
class BreadcrumbStubComponent {}

export interface ProtocolShellSpec<S> {
  /** The shell component class. */
  component: Type<unknown>;
  repoType: RepoType;
  /** The protocol service class the shell loads the permissions through. */
  service: Type<S>;
  /** The service method that requests the permissions (`selectRepository` on NuGet and PyPI). */
  loadMethod: 'getRepository' | 'selectRepository';
  /** The shell property that holds the loaded permissions. */
  permissionsField: 'permissions' | 'activeRepo' | 'activeRegistry';
}

export function describeProtocolShell<S>(spec: ProtocolShellSpec<S>): void {
  const repoName = `${spec.repoType}-repo`;

  describe(`${spec.repoType} shell permissions`, () => {
    let currentRepo$: BehaviorSubject<RepoContext | null>;
    let load: jasmine.Spy<(repoName: string) => Observable<RepoPermissionInfo>>;
    let router: jasmine.SpyObj<Router>;

    function create(authenticated = true): Record<string, unknown> {
      TestBed.overrideProvider(AuthService, {
        useValue: jasmine.createSpyObj<AuthService>('AuthService', { isAuthenticated: authenticated }),
      });
      const fixture = TestBed.createComponent(spec.component);
      fixture.detectChanges();
      return fixture.componentInstance as Record<string, unknown>;
    }

    beforeEach(() => {
      currentRepo$ = new BehaviorSubject<RepoContext | null>({ repoName, repoType: spec.repoType });
      load = jasmine.createSpy('load').and.returnValue(of(permission(repoName, { canManage: true })));
      router = jasmine.createSpyObj<Router>('Router', ['navigate']);

      TestBed.configureTestingModule({
        imports: [spec.component],
        providers: [
          { provide: spec.service, useValue: { [spec.loadMethod]: load } },
          { provide: Router, useValue: router },
          { provide: AuthService, useValue: jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated']) },
          {
            provide: RepoLookupService,
            useValue: {
              currentRepo$: currentRepo$.asObservable(),
              get currentRepo(): RepoContext | null {
                return currentRepo$.getValue();
              },
            },
          },
        ],
      });
      TestBed.overrideComponent(spec.component, {
        remove: { imports: [RepositoryBreadcrumbComponent] },
        add: { imports: [BreadcrumbStubComponent] },
      });
    });

    it('requests the permissions of the current repository once on a cold load (RPS-1305)', () => {
      const component = create();

      expect(load).toHaveBeenCalledOnceWith(repoName);
      expect((component[spec.permissionsField] as RepoPermissionInfo).canManage).toBeTrue();
      expect(component['loading']).toBeFalse();
    });

    it('requests the permissions again when the current repository changes', () => {
      create();

      currentRepo$.next({ repoName: 'other-repo', repoType: spec.repoType });

      expect(load.calls.allArgs()).toEqual([[repoName], ['other-repo']]);
    });

    it('ignores a current repository of another type', () => {
      currentRepo$.next({ repoName: 'other-repo', repoType: spec.repoType === 'npm' ? 'maven' : 'npm' });

      create();

      expect(load).not.toHaveBeenCalled();
    });

    it('sends an anonymous visitor of a private repository to not-found', () => {
      load.and.returnValue(of({ ...permission(repoName), private: true }));

      create(false);

      expect(router.navigate).toHaveBeenCalledTimes(1);
      expect(router.navigate.calls.mostRecent().args[0]).toEqual(['/not-found']);
    });

    it('marks a public repository as a public view for an anonymous visitor', () => {
      const component = create(false);

      expect(component['isPublicView']).toBeTrue();
    });

    it('sends the visitor to not-found when the permissions cannot be loaded', () => {
      load.and.returnValue(throwError(() => new Error('boom')));

      create();

      expect(router.navigate).toHaveBeenCalledTimes(1);
      expect(router.navigate.calls.mostRecent().args[0]).toEqual(['/not-found']);
    });
  });
}
