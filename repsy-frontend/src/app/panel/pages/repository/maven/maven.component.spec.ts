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

import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';

import { RepoPermissionInfo } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import { RepositoryBreadcrumbComponent } from '../breadcrumb/repository-breadcrumb.component';
import { RepoContext, RepoLookupService } from '../repo-entry/repo-lookup.service';
import { permission } from '../testing/protocol-service-spec-helpers';
import { MavenComponent } from './maven.component';
import { MavenService } from './service/maven.service';

@Component({ selector: 'app-repository-breadcrumb', standalone: true, template: '' })
class BreadcrumbStubComponent {}

describe('MavenComponent', () => {
  let currentRepo$: BehaviorSubject<RepoContext | null>;
  let mavenService: jasmine.SpyObj<MavenService>;
  let router: jasmine.SpyObj<Router>;

  function create(authenticated = true): MavenComponent {
    TestBed.overrideProvider(AuthService, {
      useValue: jasmine.createSpyObj<AuthService>('AuthService', { isAuthenticated: authenticated }),
    });
    const fixture = TestBed.createComponent(MavenComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    currentRepo$ = new BehaviorSubject<RepoContext | null>({ repoName: 'maven-repo', repoType: 'maven' });
    mavenService = jasmine.createSpyObj<MavenService>('MavenService', ['getRepository']);
    mavenService.getRepository.and.returnValue(of(permission('maven-repo', { canManage: true })));
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    TestBed.configureTestingModule({
      imports: [MavenComponent],
      providers: [
        { provide: MavenService, useValue: mavenService },
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
    TestBed.overrideComponent(MavenComponent, {
      remove: { imports: [RepositoryBreadcrumbComponent] },
      add: { imports: [BreadcrumbStubComponent] },
    });
  });

  it('loads the permissions of the current repository once on a cold load (RPS-1297)', () => {
    const component = create();

    expect(mavenService.getRepository).toHaveBeenCalledOnceWith('maven-repo');
    expect(component.permissions?.canManage).toBeTrue();
    expect(component.loading).toBeFalse();
  });

  it('loads the permissions again when the current repository changes', () => {
    create();

    currentRepo$.next({ repoName: 'other-repo', repoType: 'maven' });

    expect(mavenService.getRepository.calls.allArgs()).toEqual([['maven-repo'], ['other-repo']]);
  });

  it('ignores a current repository of another type', () => {
    currentRepo$.next({ repoName: 'npm-repo', repoType: 'npm' });

    create();

    expect(mavenService.getRepository).not.toHaveBeenCalled();
  });

  it('sends an anonymous visitor of a private repository to not-found', () => {
    mavenService.getRepository.and.returnValue(
      of({ ...permission('maven-repo'), private: true } as RepoPermissionInfo),
    );

    create(false);

    expect(router.navigate).toHaveBeenCalledWith(
      ['/not-found'],
      jasmine.objectContaining({ queryParams: jasmine.anything() }),
    );
  });
});
