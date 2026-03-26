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

import { NgOptimizedImage } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';

import { RepoLookupService } from '../repo-entry/repo-lookup.service';

@Component({
  selector: 'app-repository-breadcrumb',
  standalone: true,
  imports: [RouterModule, NgOptimizedImage],
  templateUrl: './repository-breadcrumb.component.html',
})
export class RepositoryBreadcrumbComponent implements OnInit, OnDestroy {
  public crumbs: string[] = [];
  public crumbLinks: string[] = [];
  public repoIcon = '';
  private routerSubscription: Subscription | null = null;

  constructor(
    private readonly router: Router,
    private readonly repoLookupService: RepoLookupService,
  ) {}

  public ngOnInit(): void {
    this.updateBreadcrumbs();

    this.routerSubscription = this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => {
        this.updateBreadcrumbs();
      });
  }

  public ngOnDestroy(): void {
    if (this.routerSubscription) {
      this.routerSubscription.unsubscribe();
    }
  }

  private updateBreadcrumbs(): void {
    const currentRepo = this.repoLookupService.currentRepo;

    this.crumbs = [];
    this.crumbLinks = [];

    if (!currentRepo) {
      return;
    }

    const { repoName, repoType } = currentRepo;

    this.repoIcon = this.getIconForType(repoType);

    this.crumbs.push('Repositories');
    this.crumbLinks.push('/repositories');
    this.crumbs.push(repoName);
    this.crumbLinks.push(`/${repoName}`);

    const currentUrl = this.router.url;
    const urlTree = this.router.parseUrl(currentUrl);
    const primary = urlTree.root.children['primary'];

    if (!primary?.segments) {
      return;
    }

    const segments = primary.segments;

    let currentLink = `/${repoName}`;

    for (let i = 1; i < segments.length; i++) {
      const segment = segments[i];
      let path = segment.path;

      currentLink = `${currentLink}/${path}`;

      if (path === '~') {
        continue;
      }

      if (repoType === 'npm' && i === 2 && segments.at(-1).path !== 'settings') {
        path = `@${path}`;
      }

      this.crumbs.push(path);
      this.crumbLinks.push(currentLink);
    }
  }

  private getIconForType(type: string): string {
    switch (type) {
      case 'maven':
        return 'assets/icons/repo/maven.svg';
      case 'npm':
        return 'assets/icons/repo/npm.svg';
      case 'pypi':
        return 'assets/icons/repo/pypi.svg';
      case 'docker':
        return 'assets/icons/repo/docker.svg';
      default:
        return '';
    }
  }
}
