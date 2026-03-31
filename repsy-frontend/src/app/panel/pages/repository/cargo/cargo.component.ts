import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';

import { AuthService } from '../../../../auth/pages/service/auth.service';
import { RepoPermissionInfo } from '../../../shared/dto/repo/repo-permission-info';
import { RepositoryBreadcrumbComponent } from '../breadcrumb/repository-breadcrumb.component';
import { RepoContext, RepoLookupService } from '../repo-entry/repo-lookup.service';
import { CargoService } from './service/cargo.service';

@Component({
  selector: 'app-cargo',
  templateUrl: './cargo.component.html',
  standalone: true,
  imports: [RouterOutlet, RepositoryBreadcrumbComponent],
})
export class CargoComponent implements OnInit, OnDestroy {
  public permissions: RepoPermissionInfo | null = null;
  public loading = true;
  public isAuthenticated = false;
  public isPublicView = false;

  private repoSubscription: Subscription | null = null;

  constructor(
    private readonly repoLookupService: RepoLookupService,
    private readonly cargoService: CargoService,
    private readonly authService: AuthService,
    private readonly router: Router,
  ) {}

  public ngOnInit(): void {
    this.isAuthenticated = this.authService.isAuthenticated();

    this.repoSubscription = this.repoLookupService.currentRepo$
      .pipe(filter((repo): repo is RepoContext => repo !== null && repo.repoType === 'cargo'))
      .subscribe((repoContext) => {
        this.loadPermissions(repoContext.repoName);
      });

    const currentRepo = this.repoLookupService.currentRepo;
    if (currentRepo?.repoType === 'cargo') {
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

    this.cargoService.selectRepository(repoName).subscribe({
      next: (permissions: RepoPermissionInfo) => {
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
