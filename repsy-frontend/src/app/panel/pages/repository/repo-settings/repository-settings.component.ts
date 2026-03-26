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
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { Observable, Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';

import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../shared/dto/repo/repo-permission-info';
import { RepoType } from '../../../shared/dto/repo/repo-type';
import { DockerService } from '../docker/service/docker.service';
import { MavenRepoSettingsForm } from '../maven/dto/maven-repo-settings-form';
import { MavenService } from '../maven/service/maven.service';
import { NpmService } from '../npm/service/npm.service';
import { RepositorySettingsInfo } from '../pypi/dto/repository-settings-info';
import { PypiService } from '../pypi/service/pypi.service';
import { RepoLookupService } from '../repo-entry/repo-lookup.service';
import { DeleteRepoComponent } from './delete-repo/delete-repo.component';
import { DeployTokenComponent } from './deploy-token/deploy-token.component';
import { VersionAllowenceComponent } from './maven-version-allowence/maven-version-allowence.component';
import { PackageOverrideComponent } from './package-override/package-override.component';
import { RepoInfoComponent } from './repo-info/repo-info.component';
import { RepoStorageComponent } from './repo-storage/repo-storage.component';
import { SignatureComponent } from './signature/signature.component';
import { VisibilityComponent } from './visibility/visibility.component';

@Component({
  selector: 'app-repository-settings',
  templateUrl: './repository-settings.component.html',
  standalone: true,
  imports: [
    RouterModule,
    ReactiveFormsModule,
    SpinnerComponent,
    VisibilityComponent,
    RepoInfoComponent,
    NgOptimizedImage,
    DeleteRepoComponent,
    VersionAllowenceComponent,
    RepoStorageComponent,
    PackageOverrideComponent,
    SignatureComponent,
    DeployTokenComponent,
  ],
})
export class RepositorySettingsComponent implements OnInit, OnDestroy {
  public loading = true;

  public repoType: string;
  public activeRepository: RepoPermissionInfo;
  public repositorySettings: RepositorySettingsInfo;
  public mavenRepositorySettings: MavenRepoSettingsForm;

  private repositoryChanges$: Subscription;
  private repoContext$: Subscription;

  public generalSettingsForm: FormGroup;
  public mavenSettingsForm: FormGroup;

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly toastService: ToastService,
    private readonly router: Router,
    private readonly repoLookupService: RepoLookupService,
  ) {
    this.generalSettingsForm = new FormGroup({
      privateRepository: new FormControl(false, [Validators.required]),
      allowOverride: new FormControl(true, [Validators.required]),
    });

    this.mavenSettingsForm = new FormGroup({
      privateRepository: new FormControl(false, [Validators.required]),
      releases: new FormControl(false, [Validators.required]),
      snapshots: new FormControl(false, [Validators.required]),
      allowOverride: new FormControl(true, [Validators.required]),
    });
  }

  public ngOnInit(): void {
    this.repoContext$ = this.repoLookupService.currentRepo$
      .pipe(filter((context) => !!context))
      .subscribe((context) => {
        this.repoType = context.repoType;

        this.subscription();
      });
  }

  public ngOnDestroy(): void {
    if (this.repositoryChanges$) {
      this.repositoryChanges$.unsubscribe();
    }
    if (this.repoContext$) {
      this.repoContext$.unsubscribe();
    }
  }

  public subscription() {
    this.repositoryChanges$ = this.subscriptionService().subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.loading = true;
        this.activeRepository = repo;
        if (this.activeRepository.canManage) {
          this.getRepoSettings();
        } else {
          this.router.navigate(['/repositories']);
        }
      }
    });
  }

  public getRepoSettings() {
    this.getRepoSettingsService()
      .then((res: RepositorySettingsInfo) => {
        if (this.repoType === RepoType.NPM) {
          this.repositorySettings = res as RepositorySettingsInfo;
          this.generalSettingsForm.patchValue({
            privateRepository: this.repositorySettings.privateRepo,
            ...this.repositorySettings,
          });
        } else if (this.repoType === RepoType.MAVEN) {
          this.mavenRepositorySettings = res as MavenRepoSettingsForm;
          this.mavenSettingsForm.patchValue({
            privateRepository: this.mavenRepositorySettings.privateRepo,
            ...this.mavenRepositorySettings,
          });
        } else {
          this.repositorySettings = res as RepositorySettingsInfo;
          this.generalSettingsForm.patchValue({
            privateRepository: this.repositorySettings.privateRepo,
            ...this.repositorySettings,
          });
        }
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  private subscriptionService(): Observable<RepoPermissionInfo> {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.repoChanges;
      }
      case RepoType.NPM: {
        return this.npmService.registryChanges;
      }
      case RepoType.PYPI: {
        return this.pypiService.repoChanges;
      }
      case RepoType.DOCKER: {
        return this.dockerService.repoChanges;
      }
      default:
        throw new Error('Unsupported repository type');
    }
  }

  private getRepoSettingsService(): Promise<RepositorySettingsInfo> {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.getRepoSettings();
      }
      case RepoType.NPM: {
        return this.npmService.fetchRegistrySettings();
      }
      case RepoType.PYPI: {
        return this.pypiService.fetchRepositorySettings();
      }
      case RepoType.DOCKER: {
        return this.dockerService.fetchRepositorySettings();
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }
}
