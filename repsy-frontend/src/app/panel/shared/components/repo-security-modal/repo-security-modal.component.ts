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

import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { Router } from '@angular/router';
import { finalize } from 'rxjs/operators';

import { RecentScannedVersion, RepoSecurityDetail, RepoType } from '../../../../../generated/api';
import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { SecurityService } from '../../../pages/security/service/security.service';
import { buildArtifactDetailRoute } from '../../util/security-detail-route.util';
import { SeverityBadgeComponent } from '../severity-badge/severity-badge.component';
import { SeverityBreakdownComponent } from '../severity-breakdown/severity-breakdown.component';

@Component({
  selector: 'app-repo-security-modal',
  standalone: true,
  imports: [CommonModule, SpinnerComponent, SeverityBadgeComponent, SeverityBreakdownComponent],
  templateUrl: './repo-security-modal.component.html',
})
export class RepoSecurityModalComponent implements OnChanges {
  @Input() public open = false;
  @Output() public openChange = new EventEmitter<boolean>();
  @Input({ required: true }) public repoName: string;
  @Input({ required: true }) public repoType: string;

  public loading = false;
  public detail: RepoSecurityDetail | null = null;

  constructor(
    private readonly securityService: SecurityService,
    private readonly router: Router,
  ) {}

  public ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open && this.repoName) {
      this.fetchDetail();
    }
  }

  public closeModal(): void {
    this.openChange.emit(false);
  }

  public isRecentScanClickable(scan: RecentScannedVersion): boolean {
    return this.buildRecentScanRoute(scan) !== null;
  }

  public openRecentScan(scan: RecentScannedVersion, event: Event): void {
    event.stopPropagation();

    const route = this.buildRecentScanRoute(scan);
    if (!route) {
      return;
    }

    this.closeModal();

    if (route.queryParams) {
      this.router.navigate([route.path], { queryParams: route.queryParams, fragment: 'security' });
    } else {
      this.router.navigateByUrl(`${route.path}#security`);
    }
  }

  private buildRecentScanRoute(scan: RecentScannedVersion) {
    return buildArtifactDetailRoute(
      this.repoType.toUpperCase() as RepoType,
      this.repoName,
      scan.artifactName,
      scan.artifactVersion,
    );
  }

  private fetchDetail(): void {
    this.loading = true;
    this.detail = null;

    this.securityService
      .getRepoSecurityDetail(this.repoName)
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (detail) => {
          this.detail = detail;
        },
        error: () => {},
      });
  }
}
