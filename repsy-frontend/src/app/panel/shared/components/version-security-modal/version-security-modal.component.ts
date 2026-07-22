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

import { RepoType, ScanOverview, VulnerabilityScanControllerService } from '../../../../../generated/api';
import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { buildArtifactDetailRoute } from '../../util/security-detail-route.util';
import { SeverityBreakdownComponent } from '../severity-breakdown/severity-breakdown.component';

@Component({
  selector: 'app-version-security-modal',
  standalone: true,
  imports: [CommonModule, SpinnerComponent, SeverityBreakdownComponent],
  templateUrl: './version-security-modal.component.html',
})
export class VersionSecurityModalComponent implements OnChanges {
  @Input() public open = false;
  @Output() public openChange = new EventEmitter<boolean>();
  @Input({ required: true }) public repoName: string;
  @Input({ required: true }) public repoType: string;
  @Input({ required: true }) public artifactName: string;
  @Input({ required: true }) public artifactVersion: string;

  public loading = false;
  public overview: ScanOverview | null = null;

  constructor(
    private readonly vulnerabilityScanControllerService: VulnerabilityScanControllerService,
    private readonly router: Router,
  ) {}

  public ngOnChanges(changes: SimpleChanges): void {
    if (changes['open'] && this.open && this.repoName && this.artifactName && this.artifactVersion) {
      this.fetchOverview();
    }
  }

  public closeModal(): void {
    this.openChange.emit(false);
  }

  public get isDetailClickable(): boolean {
    return this.buildDetailRoute() !== null;
  }

  public openDetail(event: Event): void {
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
      this.repoType.toUpperCase() as RepoType,
      this.repoName,
      this.artifactName,
      this.artifactVersion,
    );
  }

  private fetchOverview(): void {
    this.loading = true;
    this.overview = null;

    this.vulnerabilityScanControllerService
      .getScanOverview(this.artifactName, this.artifactVersion, this.repoName)
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (response) => {
          this.overview = response.data ?? null;
        },
        error: () => {},
      });
  }
}
