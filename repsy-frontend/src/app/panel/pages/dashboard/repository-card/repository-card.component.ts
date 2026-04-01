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
  @Input() golangRepoCount: number;

  routeMaven() {
    this.router.navigate(['/repositories'], {
      state: { repoType: 'maven' },
    });
  }

  routeNpm() {
    this.router.navigate(['/repositories'], {
      state: { repoType: 'npm' },
    });
  }

  routePypi() {
    this.router.navigate(['/repositories'], {
      state: { repoType: 'pypi' },
    });
  }

  routeDocker() {
    this.router.navigate(['/repositories'], {
      state: { repoType: 'docker' },
    });
  }

  routeGolang() {
    this.router.navigate(['/repositories'], {
      state: { repoType: 'golang' },
    });
  }
}
