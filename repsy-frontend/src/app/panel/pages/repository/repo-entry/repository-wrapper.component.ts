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

import { Component, DestroyRef, inject, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterOutlet } from '@angular/router';

import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { RepoRouteData } from './repo-type.resolver';

@Component({
  selector: 'app-repository-wrapper',
  standalone: true,
  imports: [RouterOutlet, SpinnerComponent],
  template: `
    @if (loading) {
      <app-spinner />
    } @else {
      <router-outlet />
    }
  `,
})
export class RepositoryWrapperComponent implements OnInit {
  public loading = true;
  public repoData: RepoRouteData | null = null;

  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  public ngOnInit(): void {
    this.route.data.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((data) => {
      this.repoData = data['repoData'] as RepoRouteData | null;

      if (!this.repoData) {
        return;
      }

      this.loading = false;
    });
  }
}
