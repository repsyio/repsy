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
import { Subscription } from 'rxjs';
import { filter, finalize, map, switchMap } from 'rxjs/operators';

import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { RepoType } from '../../../shared/dto/repo/repo-type';
import { MavenRepoSettingsForm } from '../maven/dto/maven-repo-settings-form';
import {
  ProtocolRepoControllerService,
  RepoInfo,
  RepoPermissionInfo,
  RepoSettingsInfo,
} from '../../../../../generated/api';
import { RepoLookupService } from '../repo-entry/repo-lookup.service';
import { DeleteRepoComponent } from './delete-repo/delete-repo.component';
import { DeployTokenComponent } from './deploy-token/deploy-token.component';
import { VersionAllowanceComponent } from './maven-version-allowence/maven-version-allowence.component';
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
    VersionAllowanceComponent,
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
  public repositorySettings: RepoSettingsInfo;
  public mavenRepositorySettings: MavenRepoSettingsForm;

  private repoContext$: Subscription;

  public generalSettingsForm: FormGroup;
  public mavenSettingsForm: FormGroup;

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
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
    this.repoContext$ = this.repoLookupService.currentRepo$.pipe(
      filter((context) => !!context),
      switchMap((context) => {
        this.repoType = context.repoType;
        return this.protocolRepoControllerService.getPermission({} as RepoPermissionInfo, context.repoName).pipe(
          map(r => r.data!),
        );
      }),
    ).subscribe((repo: RepoPermissionInfo) => {
      this.loading = true;
      this.activeRepository = repo;
      if (this.activeRepository.canManage) {
        this.getRepoSettings();
      } else {
        this.router.navigate(['/repositories']);
      }
    });
  }

  public ngOnDestroy(): void {
    if (this.repoContext$) {
      this.repoContext$.unsubscribe();
    }
  }

  public getRepoSettings() {
    this.protocolRepoControllerService.getSettings({} as RepoInfo, this.activeRepository.repoName).pipe(
      map(r => r.data!),
      finalize(() => { this.loading = false; }),
    ).subscribe({
      next: (res: RepoSettingsInfo) => {
        if (this.repoType === RepoType.NPM) {
          this.repositorySettings = res as RepoSettingsInfo;
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
          this.repositorySettings = res as RepoSettingsInfo;
          this.generalSettingsForm.patchValue({
            privateRepository: this.repositorySettings.privateRepo,
            ...this.repositorySettings,
          });
        }
      },
      error: () => {},
    });
  }
}
