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

import { Severity } from '../../../../../generated/api';
import { SecurityScanSupportService } from '../../services/security-scan-support.service';
import { RepoSecurityModalComponent } from '../repo-security-modal/repo-security-modal.component';
import { SeverityBadgeComponent } from '../severity-badge/severity-badge.component';

@Component({
  selector: 'app-repo-security-badge',
  standalone: true,
  imports: [CommonModule, SeverityBadgeComponent, RepoSecurityModalComponent],
  templateUrl: './repo-security-badge.component.html',
})
export class RepoSecurityBadgeComponent implements OnInit {
  @Input({ required: true }) repoName: string;
  @Input({ required: true }) repoType: string;
  @Input() severity: Severity | null = null;
  @Input() scanned = false;
  @Input() rescanInProgressCount: number | null = null;
  @Input() rescanFailedCount: number | null = null;
  @Input() unscannedInProgressCount: number | null = null;
  @Input() unscannedFailedCount: number | null = null;

  isSupported$: Observable<boolean>;
  showModal = false;

  constructor(private readonly securityScanSupportService: SecurityScanSupportService) {}

  ngOnInit(): void {
    this.isSupported$ = this.securityScanSupportService.isSupported(this.repoType);
  }

  /**
   * Opens the modal. The click is not stopped: the badge is a sibling of the row's link, not a child, so it
   * cannot open the row, and it has to reach the document so that an open row menu or selector closes (RPS-1565).
   */
  openModal(): void {
    this.showModal = true;
  }
}
