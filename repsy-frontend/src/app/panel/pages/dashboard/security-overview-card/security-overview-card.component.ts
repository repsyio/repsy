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
import { Component, OnInit } from '@angular/core';
import { finalize } from 'rxjs';

import { Severity } from '../../../../../generated/api';
import { SpinnerComponent } from '../../../../shared/components/spinner/spinner.component';
import { SecurityService } from '../../security/service/security.service';

@Component({
  selector: 'app-security-overview-card',
  standalone: true,
  imports: [CommonModule, SpinnerComponent],
  templateUrl: './security-overview-card.component.html',
})
export class SecurityOverviewCardComponent implements OnInit {
  public loading = true;
  public totalRepoCount = 0;
  public criticalOrHighCount = 0;

  constructor(private readonly securityService: SecurityService) {}

  public ngOnInit(): void {
    this.securityService
      .getSecuritySummary()
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (summary) => {
          const repoSummaries = Object.values(summary);
          this.totalRepoCount = repoSummaries.length;

          this.criticalOrHighCount = repoSummaries.filter(
            (repoSummary) => repoSummary.severity === Severity.Critical || repoSummary.severity === Severity.High,
          ).length;
        },
        error: () => {},
      });
  }
}
