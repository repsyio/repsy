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
import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-breadcrumb',
  standalone: true,
  imports: [RouterModule, NgOptimizedImage],
  templateUrl: './breadcrumb.component.html',
})
export class BreadcrumbComponent implements OnInit, OnDestroy {
  @Input()
  public crumbs: string[] = [];
  public crumbLinks: string[] = [];
  public username: string;

  private readonly params$: Subscription;

  constructor(private readonly router: Router) {}

  ngOnInit() {
    this.username = localStorage.getItem('username');
    this.updateBreadcrumbs();

    this.router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        this.updateBreadcrumbs();
      }
    });
  }

  private updateBreadcrumbs() {
    const urlTree = this.router.parseUrl(location.pathname);
    const primary = urlTree.root.children['primary'];

    this.crumbs = [this.username];
    this.crumbLinks = ['/'];

    if (!primary || !primary.segments) {
      return;
    }

    const segments = primary.segments;

    let currentLink = '';

    segments.forEach((segment) => {
      const path = segment.path;
      currentLink = currentLink + '/' + path;

      this.crumbs.push(path);
      this.crumbLinks.push(currentLink);
    });
  }

  ngOnDestroy() {
    if (this.params$) {
      this.params$.unsubscribe();
    }
  }
}
