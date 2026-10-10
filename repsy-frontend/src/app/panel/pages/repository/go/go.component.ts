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

import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';

import { RepoPermissionInfo } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import { RepositoryBreadcrumbComponent } from '../breadcrumb/repository-breadcrumb.component';
import { currentRepoOfType } from '../repo-entry/current-repo-of-type';
import { RepoLookupService } from '../repo-entry/repo-lookup.service';
import { GoService } from './service/go.service';

@Component({
  selector: 'app-go',
  templateUrl: './go.component.html',
  standalone: true,
  imports: [RouterOutlet, RepositoryBreadcrumbComponent],
})
export class GoComponent implements OnInit, OnDestroy {
  activeRepo: RepoPermissionInfo | null = null;
  loading = true;
  isAuthenticated = false;
  isPublicView = false;

  private repoSubscription: Subscription | null = null;

  constructor(
    private readonly repoLookupService: RepoLookupService,
    private readonly goService: GoService,
    private readonly authService: AuthService,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.isAuthenticated = this.authService.isAuthenticated();

    this.repoSubscription = currentRepoOfType(this.repoLookupService, 'golang').subscribe((repoContext) => {
      this.loadRepo(repoContext.repoName);
    });
  }

  ngOnDestroy(): void {
    if (this.repoSubscription) {
      this.repoSubscription.unsubscribe();
    }
  }

  private loadRepo(repoName: string): void {
    this.loading = true;

    this.goService.getRepository(repoName).subscribe({
      next: (repo: RepoPermissionInfo) => {
        if (repo.private && !this.isAuthenticated) {
          this.router.navigate(['/not-found'], {
            replaceUrl: true,
            queryParams: {
              message: `Repository '${repoName}' not found`,
            },
          });
          return;
        }

        this.activeRepo = repo;
        this.isPublicView = !repo.private && !this.isAuthenticated;
        this.loading = false;
      },
      error: () => {
        this.router.navigate(['/not-found'], { replaceUrl: true });
      },
    });
  }
}
