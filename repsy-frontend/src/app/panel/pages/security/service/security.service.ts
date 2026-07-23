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
import { map,Observable } from 'rxjs';

import {
  PagedModelVulnerabilityScanInfo,
  RepoSecurityDetail,
  RepoType,
  SecurityScanControllerService,
  SecurityScansSummary,
  Severity,
  VersionSecuritySummary,
  VulnerabilityScanControllerService,
} from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';

@Injectable({
  providedIn: 'root',
})
export class SecurityService {
  constructor(
    private readonly securityScanControllerService: SecurityScanControllerService,
    private readonly vulnerabilityScanControllerService: VulnerabilityScanControllerService,
    private readonly authService: AuthService,
  ) {}

  private get authorizationHeader(): string {
    return `Bearer ${this.authService.accessToken}`;
  }

  public listScans(
    severity?: Severity,
    repoType?: RepoType,
    repoName?: string,
    page?: number,
    size?: number,
  ): Observable<PagedModelVulnerabilityScanInfo> {
    return this.securityScanControllerService
      .listSecurityScans(this.authorizationHeader, severity, repoType, repoName, page, size)
      .pipe(map((r) => r.data!));
  }

  public getSecuritySummary(repoNames?: string[]): Observable<Record<string, Severity | null>> {
    return this.vulnerabilityScanControllerService
      .getSecuritySummary(this.authorizationHeader, repoNames)
      .pipe(map((r) => (r.data ?? {}) as Record<string, Severity | null>));
  }

  public getVersionSecuritySummary(
    repoName: string,
    artifactName: string,
  ): Observable<Record<string, VersionSecuritySummary>> {
    return this.vulnerabilityScanControllerService
      .getVersionSecuritySummary(artifactName, repoName)
      .pipe(map((r) => r.data ?? {}));
  }

  public getArtifactSecuritySummary(repoName: string): Observable<Record<string, VersionSecuritySummary>> {
    return this.vulnerabilityScanControllerService
      .getArtifactSecuritySummary(repoName)
      .pipe(map((r) => r.data ?? {}));
  }

  public getRepoSecurityDetail(repoName: string): Observable<RepoSecurityDetail> {
    return this.vulnerabilityScanControllerService
      .getRepoSecurityDetail(repoName)
      .pipe(map((r) => r.data!));
  }

  public getArtifactSecurityDetail(repoName: string, artifactName: string): Observable<RepoSecurityDetail> {
    return this.vulnerabilityScanControllerService
      .getArtifactSecurityDetail(artifactName, repoName)
      .pipe(map((r) => r.data!));
  }

  public getScansSummary(repoType?: RepoType, repoName?: string): Observable<SecurityScansSummary> {
    return this.securityScanControllerService
      .getSecurityScansSummary(this.authorizationHeader, repoType, repoName)
      .pipe(map((r) => r.data!));
  }
}
