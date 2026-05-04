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

import { CommonModule, NgOptimizedImage } from '@angular/common';
import { Component, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { Subscription } from 'rxjs';

import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { NugetDeletedItem } from '../../dto/nuget-deleted-item';
import { NugetDependencyInfo } from '../../dto/nuget-dependency-info';
import { NugetVersionInfo } from '../../dto/nuget-version-info';
import { NugetService } from '../../service/nuget.service';
import { environment } from '../../../../../../../environments/environment';

@Component({
  selector: 'app-nuget-packages-version-detail',
  standalone: true,
  imports: [CommonModule, SpinnerComponent, CopyClipboardComponent, NgOptimizedImage, Highlight],
  templateUrl: './nuget-packages-version-detail.component.html',
})
export class NugetPackagesVersionDetailComponent implements OnDestroy {
  public loading = true;
  public error: string;
  public packageId: string;
  public versionName: string;
  public installCommand: string;
  public installCommandUrl: string;
  public packageReferenceCommand: string;
  public packageManagerCommand: string;
  public packageManagerCommandUrl: string;
  public versionInfo: NugetVersionInfo;
  public activeRepo: RepoPermissionInfo;
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly nugetService: NugetService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.activeRepo = new RepoPermissionInfo();
    this.repositoryChanges$ = this.nugetService.repoChanges.subscribe((repo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.loadVersion();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadVersion(): void {
    const packageId = this.route.snapshot.paramMap.get('packageId');
    const version = this.route.snapshot.paramMap.get('version');
    if (!packageId || !version) {
      this.loading = false;
      return;
    }

    this.packageId = packageId;
    this.versionName = version;
    const sourceUrl = `${environment.repoBaseUrl}/${this.activeRepo.repoName}/v3/index.json`;
    this.installCommand = `dotnet add package ${packageId} --version ${version} --source repsy`;
    this.installCommandUrl = `dotnet add package ${packageId} --version ${version} --source "${sourceUrl}"`;
    this.packageReferenceCommand = `<PackageReference Include="${packageId}" Version="${version}" />`;
    this.packageManagerCommand = `Install-Package ${packageId} -Version ${version} -Source repsy`;
    this.packageManagerCommandUrl = `Install-Package ${packageId} -Version ${version} -Source "${sourceUrl}"`;

    this.loading = true;
    this.nugetService
      .fetchPackageVersion(packageId, version)
      .then((versionInfo) => {
        this.versionInfo = versionInfo;
        this.error = null;
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public deleteVersion(): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.nugetService
        .deletePackageVersion(this.packageId, this.versionName)
        .then((deletedItem) => {
          const target = deletedItem === NugetDeletedItem.PACKAGE ? ['../..'] : ['..'];
          this.router.navigate(target, { relativeTo: this.route }).then(() => {
            this.toastService.show('Version deleted successfully', 'success');
          });
        })
        .catch((err: string) => this.toastService.show(err, 'error'))
        .finally(() => {
          this.loading = false;
        });
    });
  }

  public get tags(): string[] {
    if (!this.versionInfo?.tags) {
      return [];
    }
    return this.versionInfo.tags
      .split(/[,\s]+/)
      .map((item) => item.trim())
      .filter((item) => item.length > 0);
  }

  public get dependenciesByFramework(): { framework: string; deps: NugetDependencyInfo[] }[] {
    if (!this.versionInfo?.dependencies?.length) return [];
    const map = new Map<string, NugetDependencyInfo[]>();
    for (const dep of this.versionInfo.dependencies) {
      const key = dep.targetFramework || 'All Frameworks';
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(dep);
    }
    return Array.from(map.entries()).map(([framework, deps]) => ({ framework, deps }));
  }
}
