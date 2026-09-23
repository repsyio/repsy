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
import { Component, Input, OnInit } from '@angular/core';
import { Observable } from 'rxjs';

import { ScanStatus, Severity } from '../../../../../generated/api';
import { SecurityScanSupportService } from '../../service/security-scan-support.service';
import { hasRescanFailed, isRescanInProgress } from '../../util/rescan-status.util';
import { SeverityBadgeComponent } from '../severity-badge/severity-badge.component';
import { VersionSecurityModalComponent } from '../version-security-modal/version-security-modal.component';

@Component({
  selector: 'app-version-security-badge',
  standalone: true,
  imports: [CommonModule, SeverityBadgeComponent, VersionSecurityModalComponent],
  templateUrl: './version-security-badge.component.html',
})
export class VersionSecurityBadgeComponent implements OnInit {
  @Input({ required: true }) public repoName: string;
  @Input({ required: true }) public repoType: string;
  @Input({ required: true }) public artifactName: string;
  @Input({ required: true }) public versionName: string;
  @Input() public severity: Severity | null = null;
  @Input() public scanned = false;
  @Input() public scanStatus: ScanStatus | null = null;

  public isSupported$: Observable<boolean>;
  public showModal = false;

  constructor(private readonly securityScanSupportService: SecurityScanSupportService) {}

  public ngOnInit(): void {
    this.isSupported$ = this.securityScanSupportService.isSupported(this.repoType);
  }

  /** The version has no completed scan yet and its first scan is pending, queued or running. */
  public get firstScanInProgress(): boolean {
    return !this.scanned && isRescanInProgress(this.scanStatus);
  }

  /** The version has no completed scan and its scan failed. */
  public get firstScanFailed(): boolean {
    return !this.scanned && hasRescanFailed(this.scanStatus);
  }

  /** A completed scan, or a first scan that is unfinished or failed, is worth a badge. */
  public get visible(): boolean {
    return this.scanned || this.firstScanInProgress || this.firstScanFailed;
  }

  public openModal(event: Event): void {
    event.stopPropagation();
    this.showModal = true;
  }
}
