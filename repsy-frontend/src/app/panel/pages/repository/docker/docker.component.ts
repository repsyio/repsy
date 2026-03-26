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
import { DockerService } from './service/docker.service';

@Component({
  selector: 'app-docker',
  templateUrl: './docker.component.html',
  standalone: true,
  imports: [RouterOutlet, RepositoryBreadcrumbComponent],
})
export class DockerComponent implements OnInit, OnDestroy {
  public permissions: RepoPermissionInfo | null = null;
  public loading = true;
  public isAuthenticated = false;
  public isPublicView = false;

  private repoSubscription: Subscription | null = null;

  constructor(
    private readonly repoLookupService: RepoLookupService,
    private readonly dockerService: DockerService,
    private readonly authService: AuthService,
    private readonly router: Router,
  ) {}

  public ngOnInit(): void {
    this.isAuthenticated = this.authService.isAuthenticated();

    this.repoSubscription = this.repoLookupService.currentRepo$
      .pipe(filter((repo): repo is RepoContext => repo !== null && repo.repoType === 'docker'))
      .subscribe((repoContext) => {
        this.loadPermissions(repoContext.repoName);
      });

    const currentRepo = this.repoLookupService.currentRepo;
    if (currentRepo?.repoType === 'docker') {
      this.loadPermissions(currentRepo.repoName);
    }
  }

  public ngOnDestroy(): void {
    if (this.repoSubscription) {
      this.repoSubscription.unsubscribe();
    }
  }

  private loadPermissions(repoName: string): void {
    this.loading = true;

    this.dockerService.selectRepository(repoName).subscribe({
      next: (permissions: RepoPermissionInfo) => {
        // If repo is private and user is not authenticated, redirect to 404
        if (permissions.isPrivate && !this.isAuthenticated) {
          this.router.navigate(['/not-found']);
          return;
        }

        this.permissions = permissions;
        this.isPublicView = !permissions.isPrivate && !this.isAuthenticated;
        this.loading = false;
      },
      error: () => {
        this.router.navigate(['/not-found']);
      },
    });
  }
}
