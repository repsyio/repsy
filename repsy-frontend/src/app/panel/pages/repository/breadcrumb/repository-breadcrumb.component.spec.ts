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

import { DefaultUrlSerializer, NavigationEnd, Router } from '@angular/router';
import { Subject } from 'rxjs';

import { BreadcrumbSecurityLinkService } from '../../../shared/service/breadcrumb-security-link.service';
import { RepoContext, RepoLookupService, RepoType } from '../repo-entry/repo-lookup.service';
import { RepositoryBreadcrumbComponent } from './repository-breadcrumb.component';

describe('RepositoryBreadcrumbComponent', () => {
  let component: RepositoryBreadcrumbComponent;
  let events: Subject<unknown>;
  let currentUrl: string;
  let currentRepo: RepoContext | null;
  let securityLinkService: BreadcrumbSecurityLinkService;

  beforeEach(() => {
    events = new Subject<unknown>();
    currentUrl = '/';
    currentRepo = null;
    const serializer = new DefaultUrlSerializer();
    const router = {
      events,
      get url(): string {
        return currentUrl;
      },
      parseUrl: (url: string) => serializer.parse(url),
    } as unknown as Router;
    const lookup = {
      get currentRepo(): RepoContext | null {
        return currentRepo;
      },
    } as unknown as RepoLookupService;
    securityLinkService = new BreadcrumbSecurityLinkService();
    component = new RepositoryBreadcrumbComponent(router, lookup, securityLinkService);
  });

  afterEach(() => component.ngOnDestroy());

  /** Puts the app on `url`, inside a repository of `repoType` named `repoName`, and renders the breadcrumb. */
  function visit(url: string, repoType: RepoType = 'maven', repoName = 'acme-repo'): void {
    currentRepo = { repoName, repoType };
    currentUrl = url;
    component.ngOnInit();
  }

  describe('outside of a repository', () => {
    it('has no crumbs', () => {
      component.ngOnInit();

      expect(component.crumbs).toEqual([]);
      expect(component.crumbLinks).toEqual([]);
      expect(component.crumbQueryParams).toEqual([]);
    });
  });

  describe('inside a repository', () => {
    it('starts with Repositories and the repository, each linking to itself', () => {
      visit('/acme-repo');

      expect(component.crumbs).toEqual(['Repositories', 'acme-repo']);
      expect(component.crumbLinks).toEqual(['/repositories', '/acme-repo']);
      expect(component.crumbQueryParams).toEqual([null, null]);
    });

    it('adds a crumb per further path segment, each linking to the path up to it', () => {
      visit('/acme-repo/org.acme/lib/1.0.0');

      expect(component.crumbs).toEqual(['Repositories', 'acme-repo', 'org.acme', 'lib', '1.0.0']);
      expect(component.crumbLinks).toEqual([
        '/repositories',
        '/acme-repo',
        '/acme-repo/org.acme',
        '/acme-repo/org.acme/lib',
        '/acme-repo/org.acme/lib/1.0.0',
      ]);
    });

    it('ignores the query string when reading the path', () => {
      visit('/acme-repo/org.acme?page=2');

      expect(component.crumbs).toEqual(['Repositories', 'acme-repo', 'org.acme']);
    });

    it('has just the repository for a URL without a primary path', () => {
      visit('/');

      expect(component.crumbs).toEqual(['Repositories', 'acme-repo']);
    });

    it('rebuilds the crumbs from scratch on every navigation', () => {
      visit('/acme-repo/org.acme/lib');

      currentUrl = '/acme-repo/settings';
      events.next(new NavigationEnd(1, currentUrl, currentUrl));

      expect(component.crumbs).toEqual(['Repositories', 'acme-repo', 'settings']);
      expect(component.crumbLinks).toEqual(['/repositories', '/acme-repo', '/acme-repo/settings']);
      expect(component.crumbQueryParams.length).toBe(3);
    });

    it('ignores router events other than the end of a navigation', () => {
      visit('/acme-repo/org.acme');

      currentUrl = '/acme-repo/settings';
      events.next({ type: 'something else' });

      expect(component.crumbs).toEqual(['Repositories', 'acme-repo', 'org.acme']);
    });

    it('stops following the router when destroyed', () => {
      visit('/acme-repo/org.acme');
      component.ngOnDestroy();

      currentUrl = '/acme-repo/settings';
      events.next(new NavigationEnd(1, currentUrl, currentUrl));

      expect(component.crumbs).toEqual(['Repositories', 'acme-repo', 'org.acme']);
    });

    it('can be destroyed without ever having been initialised', () => {
      expect(() => component.ngOnDestroy()).not.toThrow();
    });
  });

  describe('the icon', () => {
    const icons: [RepoType, string][] = [
      ['maven', 'assets/icons/repo/maven.svg'],
      ['npm', 'assets/icons/repo/npm.svg'],
      ['pypi', 'assets/icons/repo/pypi.svg'],
      ['docker', 'assets/icons/repo/docker.svg'],
      ['golang', 'assets/icons/repo/golang.svg'],
      ['cargo', 'assets/icons/repo/cargo.svg'],
      ['helm', 'assets/icons/repo/helm.svg'],
      ['nuget', 'assets/icons/nuget/nuget.svg'],
      ['ruby', 'assets/icons/ruby/ruby.svg'],
    ];

    for (const [type, icon] of icons) {
      it(`is the ${type} icon for a ${type} repository`, () => {
        visit('/acme-repo', type);

        expect(component.repoIcon).toBe(icon);
      });
    }

    it('is empty for an unknown repository type', () => {
      visit('/acme-repo', 'unknown' as RepoType);

      expect(component.repoIcon).toBe('');
    });
  });

  describe('npm repositories', () => {
    it('leave out the ~ that stands for a package without a scope', () => {
      visit('/npm-repo/~/left-pad/1.3.0', 'npm', 'npm-repo');

      expect(component.crumbs).toEqual(['Repositories', 'npm-repo', 'left-pad', '1.3.0']);
      expect(component.crumbLinks).toEqual([
        '/repositories',
        '/npm-repo',
        '/npm-repo/~/left-pad',
        '/npm-repo/~/left-pad/1.3.0',
      ]);
    });

    it('write the scope with an @, as npm does', () => {
      visit('/npm-repo/acme/ui/1.0.0', 'npm', 'npm-repo');

      expect(component.crumbs).toEqual(['Repositories', 'npm-repo', '@acme', 'ui', '1.0.0']);
      expect(component.crumbLinks[2]).toBe('/npm-repo/acme');
    });

    it('write the scope with an @ on the scope page too', () => {
      visit('/npm-repo/acme', 'npm', 'npm-repo');

      expect(component.crumbs).toEqual(['Repositories', 'npm-repo', '@acme']);
    });

    it('do not treat the settings page as a scope', () => {
      visit('/npm-repo/settings', 'npm', 'npm-repo');

      expect(component.crumbs).toEqual(['Repositories', 'npm-repo', 'settings']);
    });
  });

  describe('Go repositories', () => {
    it('show the module path from the query in place of the modules segment, linking back to the module', () => {
      visit('/go-repo/modules?modulePath=github.com%2Facme%2Flib', 'golang', 'go-repo');

      expect(component.crumbs).toEqual(['Repositories', 'go-repo', 'github.com/acme/lib']);
      expect(component.crumbLinks[2]).toBe('/go-repo/modules');
      expect(component.crumbQueryParams[2]).toEqual({ modulePath: 'github.com/acme/lib' });
    });

    it('show the version from the query in place of the version segment', () => {
      visit('/go-repo/modules/version?modulePath=github.com%2Facme%2Flib&version=v1.2.3', 'golang', 'go-repo');

      expect(component.crumbs).toEqual(['Repositories', 'go-repo', 'github.com/acme/lib', 'v1.2.3']);
      expect(component.crumbLinks[3]).toBe('/go-repo/modules/version');
      expect(component.crumbQueryParams[3]).toBeNull();
    });

    it('show the segments as they are without those query parameters', () => {
      visit('/go-repo/modules/version', 'golang', 'go-repo');

      expect(component.crumbs).toEqual(['Repositories', 'go-repo', 'modules', 'version']);
    });

    it('do not rewrite the modules segment of another repository type', () => {
      visit('/acme-repo/modules?modulePath=x', 'maven');

      expect(component.crumbs).toEqual(['Repositories', 'acme-repo', 'modules']);
    });
  });

  it('follows the repository type the security link service announces', () => {
    const seen: (string | null)[] = [];
    component.securityLinkRepoType$.subscribe((type) => seen.push(type));

    securityLinkService.show('maven');
    securityLinkService.clear();

    expect(seen).toEqual([null, 'maven', null]);
  });
});
