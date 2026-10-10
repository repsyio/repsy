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

import { ScanOverview, ScanStatus, SecurityScansApi } from '../../../../../generated/api';
import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { DialogDirective } from '../../directives/dialog.directive';
import { PortalToBodyDirective } from '../../directives/portal-to-body.directive';
import { toApiRepoType } from '../../utils/repo-api-type';
import { recentScanNote } from '../../utils/rescan-status.utils';
import { splitScopedArtifactName } from '../../utils/scoped-artifact.utils';
import { buildArtifactDetailRoute } from '../../utils/security-detail-route.utils';
import { RescanNoteComponent } from '../rescan-note/rescan-note.component';
import { ScanFailureReasonComponent } from '../scan-failure-reason/scan-failure-reason.component';
import { SeverityBreakdownComponent } from '../severity-breakdown/severity-breakdown.component';

@Component({
  selector: 'app-version-security-modal',
  standalone: true,
  hostDirectives: [PortalToBodyDirective],
  imports: [
    DialogDirective,
    CommonModule,
    SpinnerComponent,
    SeverityBreakdownComponent,
    RescanNoteComponent,
    ScanFailureReasonComponent,
  ],
  templateUrl: './version-security-modal.component.html',
})
export class VersionSecurityModalComponent implements OnChanges {
  @Input() open = false;
  @Output() openChange = new EventEmitter<boolean>();
  @Input({ required: true }) repoName: string;
  @Input({ required: true }) repoType: string;
  @Input({ required: true }) artifactName: string;
  @Input({ required: true }) artifactVersion: string;

  protected readonly ScanStatus = ScanStatus;

  loading = false;
  overview: ScanOverview | null = null;

  constructor(
    private readonly securityScansApi: SecurityScansApi,
    private readonly router: Router,
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open && this.repoName && this.artifactName && this.artifactVersion) {
      this.fetchOverview();
    }
  }

  closeModal(): void {
    this.openChange.emit(false);
  }

  /** Whether the newest scan is unfinished, so the counters below are the last known ones. */
  get hasRescanNote(): boolean {
    return !!this.overview && recentScanNote(this.overview.status, !!this.overview.lastCompletedAt) !== '';
  }

  get isDetailClickable(): boolean {
    return this.buildDetailRoute() !== null;
  }

  openDetail(event: Event): void {
    event.stopPropagation();

    const route = this.buildDetailRoute();
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

  private buildDetailRoute() {
    return buildArtifactDetailRoute(
      toApiRepoType(this.repoType),
      this.repoName,
      this.artifactName,
      this.artifactVersion,
    );
  }

  private fetchOverview(): void {
    this.loading = true;
    this.overview = null;

    const scoped = splitScopedArtifactName(this.artifactName);
    const overview$ = scoped
      ? this.securityScansApi.getScopedScanOverview(scoped.scope, scoped.name, this.artifactVersion, this.repoName)
      : this.securityScansApi.getScanOverview(this.artifactName, this.artifactVersion, this.repoName);

    overview$.pipe(finalize(() => (this.loading = false))).subscribe({
      next: (response) => {
        this.overview = response ?? null;
      },
      error: () => {},
    });
  }
}
