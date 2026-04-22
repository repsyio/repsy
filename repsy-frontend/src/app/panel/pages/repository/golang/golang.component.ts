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
import { filter } from 'rxjs/operators';

import { AuthService } from '../../../../auth/pages/service/auth.service';
import { RepoPermissionInfo } from '../../../shared/dto/repo/repo-permission-info';
import { RepositoryBreadcrumbComponent } from '../breadcrumb/repository-breadcrumb.component';
import { RepoContext, RepoLookupService } from '../repo-entry/repo-lookup.service';
import { GolangService } from './service/golang.service';

@Component({
  selector: 'app-golang',
  templateUrl: './golang.component.html',
  standalone: true,
  imports: [RouterOutlet, RepositoryBreadcrumbComponent],
})
export class GolangComponent implements OnInit, OnDestroy {
  public activeRepo: RepoPermissionInfo | null = null;
  public loading = true;
  public isAuthenticated = false;
  public isPublicView = false;

  private repoSubscription: Subscription | null = null;

  constructor(
    private readonly repoLookupService: RepoLookupService,
    private readonly golangService: GolangService,
    private readonly authService: AuthService,
    private readonly router: Router,
  ) {}

  public ngOnInit(): void {
    this.isAuthenticated = this.authService.isAuthenticated();

    this.repoSubscription = this.repoLookupService.currentRepo$
      .pipe(filter((repo): repo is RepoContext => repo !== null && repo.repoType === 'golang'))
      .subscribe((repoContext) => {
        this.loadRepo(repoContext.repoName);
      });

    const currentRepo = this.repoLookupService.currentRepo;
    if (currentRepo?.repoType === 'golang') {
      this.loadRepo(currentRepo.repoName);
    }
  }

  public ngOnDestroy(): void {
    if (this.repoSubscription) {
      this.repoSubscription.unsubscribe();
    }
  }

  private loadRepo(repoName: string): void {
    this.loading = true;

    this.golangService.getRepository(repoName).subscribe({
      next: (repo: RepoPermissionInfo) => {
        if (repo.isPrivate && !this.isAuthenticated) {
          this.router.navigate(['/not-found'], {
            queryParams: {
              message: `Repository '${repoName}' not found`,
            },
          });
          return;
        }

        this.activeRepo = repo;
        this.isPublicView = !repo.isPrivate && !this.isAuthenticated;
        this.loading = false;
      },
      error: () => {
        this.router.navigate(['/not-found']);
      },
    });
  }
}
