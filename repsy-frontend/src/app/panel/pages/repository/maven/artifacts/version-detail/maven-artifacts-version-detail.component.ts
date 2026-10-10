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
import { Component, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { HighlightLineNumbers } from 'ngx-highlightjs/line-numbers';
import { Subscription } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../../environments/environment';
import {
  ArtifactVersionInfo,
  RepoPermissionInfo,
  RepoType,
  VersionDeveloperInfo,
  VersionLicenseInfo,
} from '../../../../../../../generated/api';
import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SecurityScanSectionComponent } from '../../../../../shared/components/security-scan-section/security-scan-section.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { BreadcrumbSecurityLinkService } from '../../../../../shared/services/breadcrumb-security-link.service';
import {
  deleteVersionAndCheckLast$,
  landAfterVersionDelete,
  VERSION_PROBE_SIZE,
} from '../../../../../shared/utils/version-delete-landing.utils';
import { versionLoadError } from '../../../../../shared/utils/version-load-error.utils';
import { RepoLookupService } from '../../../repo-entry/repo-lookup.service';
import { MAVEN_VERSION_PROBE_SORT, MavenService } from '../../services/maven.service';
import { showVersionDeleteDialog } from '../../utils/version-delete-warning.utils';

/** What the licenses and developers lines show when the POM declares none. */
const NO_VALUE = '-';

/**
 * The licenses of a version as one line of plain text (RPS-1425): the name of each, or its URL when it has no name,
 * sorted because the server sends them in no particular order.
 */
export function formatLicenses(licenses: VersionLicenseInfo[] | undefined | null): string {
  return joinSorted((licenses ?? []).map((license) => license?.name?.trim() || license?.url?.trim()));
}

/** The developers of a version as one line of plain text (RPS-1425): `Name <email>`, or whichever of the two is there. */
export function formatDevelopers(developers: VersionDeveloperInfo[] | undefined | null): string {
  return joinSorted(
    (developers ?? []).map((developer) => {
      const name = developer?.name?.trim();
      const email = developer?.email?.trim();
      if (name && email) {
        return `${name} <${email}>`;
      }
      return name || email;
    }),
  );
}

function joinSorted(entries: (string | undefined)[]): string {
  const shown = entries.filter((entry): entry is string => !!entry).sort((a, b) => a.localeCompare(b));
  return shown.length > 0 ? shown.join(', ') : NO_VALUE;
}

@Component({
  selector: 'app-maven-artifacts-version-detail',
  standalone: true,
  imports: [
    CopyClipboardComponent,
    NgOptimizedImage,
    SpinnerComponent,
    HighlightLineNumbers,
    Highlight,
    SecurityScanSectionComponent,
  ],
  templateUrl: './maven-artifacts-version-detail.component.html',
})
export class MavenArtifactsVersionDetailComponent implements OnDestroy {
  readonly securityRepoType = RepoType.Maven;
  loading = true;
  baseUrl: string;
  groupName: string;
  artifactName: string;
  versionName: string;
  error: string;
  activeRepo: RepoPermissionInfo;
  version: ArtifactVersionInfo;
  mavenDependencyHtml: string;
  mavenRepositoryHtml: string;
  gradleDependencyHtml: string;
  gradleKotlinDependencyHtml: string;
  sbtDependencyHtml: string;
  ivyDependencyHtml: string;
  groovyDependencyHtml: string;
  leiningenDependencyHtml: string;
  buildrDependencyHtml: string;
  purlDependencyHtml: string;
  bazelDependencyHtml: string;
  licensesText = NO_VALUE;
  developersText = NO_VALUE;

  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly mavenService: MavenService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly dangerModalService: DangerModalService,
    private readonly toastService: ToastService,
    private readonly breadcrumbSecurityLinkService: BreadcrumbSecurityLinkService,
    private readonly repoLookupService: RepoLookupService,
  ) {
    this.baseUrl = environment.apiBaseUrl;
    this.activeRepo = {} as RepoPermissionInfo;
    this.repositoryChanges$ = this.mavenService.repoChanges$.subscribe((registry: RepoPermissionInfo) => {
      if (registry && this.isRegistryForCurrentRoute(registry)) {
        this.activeRepo = Object.assign({}, registry);
        this.loadVersion();
      }
    });
    this.breadcrumbSecurityLinkService.show(RepoType.Maven);
  }

  ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
    this.breadcrumbSecurityLinkService.clear();
  }

  private isRegistryForCurrentRoute(registry: RepoPermissionInfo): boolean {
    const currentRepo = this.repoLookupService.currentRepo;
    const matches = !!currentRepo && registry.repoName === currentRepo.repoName;

    if (!matches) {
      console.debug('Ignoring stale repo registry emission for a different repo', registry, currentRepo);
    }

    return matches;
  }

  get securityArtifactName(): string {
    return `${this.version.artifactGroupName}:${this.version.artifactName}`;
  }

  loadVersion(): void {
    this.loading = true;
    this.error = null;

    this.groupName = this.route.snapshot.paramMap.get('groupName');
    this.artifactName = this.route.snapshot.paramMap.get('artifactName');
    this.versionName = this.route.snapshot.paramMap.get('version');

    this.mavenService
      .fetchArtifactVersion(this.groupName, this.artifactName, this.versionName)
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (data) => {
          this.version = data;
          this.licensesText = formatLicenses(data.licenses);
          this.developersText = formatDevelopers(data.developers);
          this.mavenDependencyHtml = `<dependency>
  <groupId>${this.version.artifactGroupName}</groupId>
  <artifactId>${this.version.artifactName}</artifactId>
  <version>${this.version.artifactVersionName}</version>
</dependency>`;
          this.mavenRepositoryHtml = `<repositories>
  <repository>
    <id>repsy</id>
    <name>${this.activeRepo.repoName} on Repsy</name>
    <url>${environment.repoBaseUrl}/${this.activeRepo.repoName}</url>
  </repository>
</repositories>`;
          this.gradleDependencyHtml = `implementation '${this.version.artifactGroupName}:${this.version.artifactName}:${this.version.artifactVersionName}'`;
          this.gradleKotlinDependencyHtml = `implementation("${this.version.artifactGroupName}:${this.version.artifactName}:${this.version.artifactVersionName}")`;
          this.sbtDependencyHtml = `libraryDependencies += "${this.version.artifactGroupName}" % "${this.version.artifactName}" % "${this.version.artifactVersionName}"`;
          this.ivyDependencyHtml = `<dependency org="${this.version.artifactGroupName}" name="${this.version.artifactName}" rev="${this.version.artifactVersionName}" conf="default->default" />`;
          this.groovyDependencyHtml = `@Grapes(
  @Grab(group='${this.version.artifactGroupName}', module='${this.version.artifactName}', version='${this.version.artifactVersionName}')
)`;
          this.leiningenDependencyHtml = `[${this.version.artifactGroupName}/${this.version.artifactName} "${this.version.artifactVersionName}"]`;
          this.buildrDependencyHtml = `'${this.version.artifactGroupName}:${this.version.artifactName}:jar:${this.version.artifactVersionName}'`;
          this.purlDependencyHtml = `pkg:maven/${this.version.artifactGroupName}/${this.version.artifactName}@${this.version.artifactVersionName}`;
          this.bazelDependencyHtml = `maven_jar(
  name = "${this.version.artifactName}",
  artifact = "${this.version.artifactGroupName}:${this.version.artifactName}:${this.version.artifactVersionName}",
  sha1 = "calculating...",
)`;
        },
        error: (err: unknown) => {
          this.version = undefined;
          this.error = versionLoadError(err, this.versionName);
        },
      });
  }

  deleteVersion() {
    this.mavenService.fetchVersionDeleteWarning(this.groupName, this.artifactName).subscribe((warning) => {
      showVersionDeleteDialog(this.dangerModalService, warning, () => this.confirmDeleteVersion());
    });
  }

  private confirmDeleteVersion() {
    this.loading = true;
    // The delete answers 204 and says nothing about what went with the version: the last version of an
    // artifact takes the artifact (and the last artifact of a group the group) with it. The versions probe
    // read before the delete tells whether this was the last one.
    deleteVersionAndCheckLast$(
      this.mavenService.searchArtifactVersions(
        this.groupName,
        this.artifactName,
        '',
        MAVEN_VERSION_PROBE_SORT,
        0,
        VERSION_PROBE_SIZE,
      ),
      () => this.mavenService.deleteVersion(this.groupName, this.artifactName, this.version.artifactVersionName),
    )
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
      )
      .subscribe({
        next: (wasLastVersion) => {
          landAfterVersionDelete(this.router, this.route, this.toastService, this.activeRepo.repoName, wasLastVersion);
        },
        error: () => {},
      });
  }
}
