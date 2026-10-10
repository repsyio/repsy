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

import { environment } from '../../../../../../../environments/environment';
import {
  NuGetDependencyInfo,
  NuGetVersionInfo,
  RepoPermissionInfo,
  RepoType,
} from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { MarkdownComponent } from '../../../../../shared/components/markdown/markdown.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { Sort } from '../../../../../shared/dto/sort';
import {
  isLastVersion,
  landAfterVersionDelete,
  VERSION_PROBE_SIZE,
} from '../../../../../shared/util/version-delete-landing.util';
import { versionLoadError } from '../../../../../shared/util/version-load-error.util';
import { NugetService } from '../../service/nuget.service';

/** The sort of the probe above: any valid one will do, the probe only counts. */
const PROBE_SORT: Sort = { name: 'Newest', column: 'publishedAt', type: 'DESC' };

@Component({
  selector: 'app-nuget-packages-version-detail',
  standalone: true,
  imports: [
    CommonModule,
    SpinnerComponent,
    CopyClipboardComponent,
    NgOptimizedImage,
    Highlight,
    SecurityScanSectionComponent,
    MarkdownComponent,
  ],
  templateUrl: './nuget-packages-version-detail.component.html',
})
export class NugetPackagesVersionDetailComponent implements OnDestroy {
  public readonly securityRepoType = RepoType.Nuget;
  public loading = true;
  public error: string;
  public packageId: string;
  public versionName: string;
  public installCommand: string;
  public installCommandUrl: string;
  public packageReferenceCommand: string;
  public packageManagerCommand: string;
  public packageManagerCommandUrl: string;
  public versionInfo: NuGetVersionInfo;
  public activeRepo: RepoPermissionInfo;
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly nugetService: NugetService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.nugetService.repoChanges.subscribe((repo) => {
      if (repo) {
        this.activeRepo = Object.assign({}, repo);
        this.loadVersion();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadVersion(): void {
    const packageId = this.route.snapshot.paramMap.get('packageName');
    const version = this.route.snapshot.paramMap.get('version');
    if (!packageId || !version) {
      this.loading = false;
      return;
    }

    this.packageId = packageId;
    this.versionName = version;
    const sourceUrl = `${environment.repoBaseUrl}/${this.activeRepo.repoName}/v3/index.json`;
    // `dotnet add package --source` only accepts a URL or a folder, never a configured source's
    // name, so once "repsy" is added to NuGet.Config the plain form (no --source) picks it up.
    this.installCommand = `dotnet add package ${packageId} --version ${version}`;
    this.installCommandUrl = `dotnet add package ${packageId} --version ${version} --source "${sourceUrl}"`;
    this.packageReferenceCommand = `<PackageReference Include="${packageId}" Version="${version}" />`;
    this.packageManagerCommand = `Install-Package ${packageId} -Version ${version} -Source repsy`;
    this.packageManagerCommandUrl = `Install-Package ${packageId} -Version ${version} -Source "${sourceUrl}"`;

    this.loading = true;
    this.error = null;
    this.nugetService
      .fetchPackageVersion(packageId, version)
      .then((versionInfo) => {
        this.versionInfo = versionInfo;
        this.error = null;
      })
      // The error interceptor has already toasted the failure; keep a message for the page.
      .catch((err: unknown) => {
        this.versionInfo = undefined;
        this.error = versionLoadError(err, version);
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public deleteVersion(): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      // Deleting the last version removes the package, so its versions page would answer 404: the page
      // to land on is decided from the versions the package has right before the delete.
      this.nugetService
        .fetchPackageVersions(this.packageId, '', PROBE_SORT, 0, VERSION_PROBE_SIZE)
        .then((probe) =>
          this.nugetService.deletePackageVersion(this.packageId, this.versionName).then(() => isLastVersion(probe)),
        )
        .then((wasLastVersion) => {
          landAfterVersionDelete(this.router, this.route, this.toastService, this.activeRepo.repoName, wasLastVersion);
        })
        // The error interceptor has already shown the failure to the user.
        .catch(() => undefined)
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

  public get dependenciesByFramework(): { framework: string; deps: NuGetDependencyInfo[] }[] {
    if (!this.versionInfo?.dependencies?.length) {
      return [];
    }
    const map = new Map<string, NuGetDependencyInfo[]>();
    for (const dep of this.versionInfo.dependencies) {
      const key = dep.targetFramework || 'All Frameworks';
      if (!map.has(key)) {
        map.set(key, []);
      }
      map.get(key)!.push(dep);
    }
    return Array.from(map.entries()).map(([framework, deps]) => ({ framework, deps }));
  }
}
