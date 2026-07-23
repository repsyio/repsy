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
import { Component, Input } from '@angular/core';

import { Severity } from '../../../../../generated/api';


const SEVERITY_CLASSES: Record<string, string> = {
  [Severity.Critical]: 'border-error-600 bg-error-900 text-error-400',
  [Severity.High]: 'border-warning-600 bg-warning-900 text-warning-400',
  [Severity.Medium]: 'border-info-600 bg-info-900 text-info-400',
  [Severity.Low]: 'border-success-600 bg-success-900 text-success-400',
  [Severity.Unknown]: 'border-neutral-400 bg-neutral-700 text-neutral-200',
};

const SEVERITY_LABELS: Record<string, string> = {
  [Severity.Critical]: 'Critical',
  [Severity.High]: 'High',
  [Severity.Medium]: 'Medium',
  [Severity.Low]: 'Low',
  [Severity.Unknown]: 'Unknown',
};

@Component({
  selector: 'app-severity-badge',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './severity-badge.component.html',
})
export class SeverityBadgeComponent {
  @Input({ required: true }) public severity: Severity;
  @Input() public count: number | null = null;
  @Input() public compact = false;

  protected readonly Severity = Severity;

  public get classes(): string {
    return SEVERITY_CLASSES[this.severity] ?? SEVERITY_CLASSES[Severity.Unknown];
  }

  public get label(): string {
    return SEVERITY_LABELS[this.severity] ?? SEVERITY_LABELS[Severity.Unknown];
  }
}
