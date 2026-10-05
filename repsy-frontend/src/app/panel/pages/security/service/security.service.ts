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

import { Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';

import {
  PagedModelVulnerabilityScanInfo,
  RepoSecurityDetail,
  RepoSecuritySummary,
  RepoType,
  SecurityScansApi,
  SecurityScansSummary,
  Severity,
  VersionSecuritySummary,
} from '../../../../../generated/api';
import { splitScopedArtifactName } from '../../../shared/util/scoped-artifact.util';
import { pollSecuritySummary } from '../../../shared/util/security-summary-poll.util';

@Injectable({
  providedIn: 'root',
})
export class SecurityService {
  constructor(private readonly securityScansApi: SecurityScansApi) {}

  public listScans(
    severity?: Severity,
    repoType?: RepoType,
    repoName?: string,
    page?: number,
    size?: number,
  ): Observable<PagedModelVulnerabilityScanInfo> {
    return this.securityScansApi.listSecurityScans(severity, repoType, repoName, page, size);
  }

  public getSecuritySummary(repoNames?: string[]): Observable<Record<string, RepoSecuritySummary>> {
    return this.securityScansApi.getSecuritySummary(repoNames).pipe(map((r) => r ?? {}));
  }

  public getVersionSecuritySummary(
    repoName: string,
    artifactName: string,
  ): Observable<Record<string, VersionSecuritySummary>> {
    const scoped = splitScopedArtifactName(artifactName);
    const summary$ = scoped
      ? this.securityScansApi.getScopedVersionSecuritySummary(scoped.scope, scoped.name, repoName)
      : this.securityScansApi.getVersionSecuritySummary(artifactName, repoName);

    return summary$.pipe(map((r) => r ?? {}));
  }

  public getArtifactSecuritySummary(repoName: string): Observable<Record<string, VersionSecuritySummary>> {
    return this.securityScansApi.getArtifactSecuritySummary(repoName).pipe(map((r) => r ?? {}));
  }

  /** The repository summary, fetched again while a scan of one of the repositories is unfinished. */
  public watchSecuritySummary(repoNames?: string[]): Observable<Record<string, RepoSecuritySummary>> {
    return pollSecuritySummary(() => this.getSecuritySummary(repoNames));
  }

  /** The per-version summary of an artifact, fetched again while a scan of one of its versions is unfinished. */
  public watchVersionSecuritySummary(
    repoName: string,
    artifactName: string,
  ): Observable<Record<string, VersionSecuritySummary>> {
    return pollSecuritySummary(() => this.getVersionSecuritySummary(repoName, artifactName));
  }

  /** The per-artifact summary of a repository, fetched again while a scan of one of its artifacts is unfinished. */
  public watchArtifactSecuritySummary(repoName: string): Observable<Record<string, VersionSecuritySummary>> {
    return pollSecuritySummary(() => this.getArtifactSecuritySummary(repoName));
  }

  public getRepoSecurityDetail(repoName: string): Observable<RepoSecurityDetail> {
    return this.securityScansApi.getRepoSecurityDetail(repoName);
  }

  public getArtifactSecurityDetail(repoName: string, artifactName: string): Observable<RepoSecurityDetail> {
    const scoped = splitScopedArtifactName(artifactName);

    return scoped
      ? this.securityScansApi.getScopedArtifactSecurityDetail(scoped.scope, scoped.name, repoName)
      : this.securityScansApi.getArtifactSecurityDetail(artifactName, repoName);
  }

  public getScansSummary(repoType?: RepoType, repoName?: string): Observable<SecurityScansSummary> {
    return this.securityScansApi.getSecurityScansSummary(repoType, repoName);
  }
}
