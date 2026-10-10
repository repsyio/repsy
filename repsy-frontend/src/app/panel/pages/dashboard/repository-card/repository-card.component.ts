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
import { Component, Input } from '@angular/core';
import { Router } from '@angular/router';

@Component({
  selector: 'app-repository-card',
  standalone: true,
  imports: [NgOptimizedImage],
  templateUrl: './repository-card.component.html',
})
export class RepositoryCardComponent {
  constructor(private readonly router: Router) {}
  @Input() mavenRepoCount: number;
  @Input() npmRegistryCount: number;
  @Input() pypiRepoCount: number;
  @Input() dockerRepoCount: number;
  @Input() goRepoCount: number;
  @Input() cargoRepoCount: number;
  @Input() helmRepoCount: number;
  @Input() nugetRepoCount: number;
  @Input() rubyRepoCount: number;

  // RPS-1668: a `type` query param, not router state, so the repository list opens pre-filtered to
  // this type even across a reload (the list itself owns restoring the rest of its state from the URL).
  routeMaven() {
    this.route('maven');
  }

  routeNpm() {
    this.route('npm');
  }

  routePypi() {
    this.route('pypi');
  }

  routeDocker() {
    this.route('docker');
  }

  routeCargo() {
    this.route('cargo');
  }

  routeGo() {
    this.route('golang');
  }

  routeHelm() {
    this.route('helm');
  }

  routeNuGet() {
    this.route('nuget');
  }

  routeRuby() {
    this.route('ruby');
  }

  private route(type: string): void {
    this.router.navigate(['/repositories'], { queryParams: { type } });
  }
}
