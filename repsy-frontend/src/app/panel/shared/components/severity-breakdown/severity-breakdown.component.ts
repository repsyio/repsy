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
import { Component, ElementRef, Input, OnChanges, OnDestroy, SimpleChanges, ViewChild } from '@angular/core';
import { Chart, registerables } from 'chart.js';

import { SeverityBadgeComponent } from '../severity-badge/severity-badge.component';

Chart.register(...registerables);

const SEVERITY_CHART_COLORS = ['#ed7688', '#ffec7d', '#7de2ff', '#9eff87', '#9fa0a0'];
const SEVERITY_CHART_LABELS = ['Critical', 'High', 'Medium', 'Low', 'Unknown'];

/**
 * The set of fields RepoSecurityDetail and ScanOverview both share — a structural type rather than
 * importing either generated model directly, so this component doesn't care which endpoint the
 * counts came from.
 */
export interface SeverityCounts {
  criticalCount?: number;
  highCount?: number;
  mediumCount?: number;
  lowCount?: number;
  unknownCount?: number;
  totalCount?: number;
}

@Component({
  selector: 'app-severity-breakdown',
  standalone: true,
  imports: [CommonModule, SeverityBadgeComponent],
  templateUrl: './severity-breakdown.component.html',
})
export class SeverityBreakdownComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) public counts: SeverityCounts | null = null;

  @ViewChild('chartCanvas') public chartCanvasRef?: ElementRef<HTMLCanvasElement>;

  private chart?: Chart;

  public get hasFindings(): boolean {
    return (this.counts?.totalCount ?? 0) > 0;
  }

  public ngOnChanges(changes: SimpleChanges): void {
    if (changes['counts'] && this.hasFindings) {
      setTimeout(() => this.renderChart());
    }
  }

  public ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private renderChart(): void {
    const canvas = this.chartCanvasRef?.nativeElement;
    const ctx = canvas?.getContext('2d');

    if (!ctx || !this.counts) {
      return;
    }

    this.chart?.destroy();

    this.chart = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: SEVERITY_CHART_LABELS,
        datasets: [
          {
            data: [
              this.counts.criticalCount ?? 0,
              this.counts.highCount ?? 0,
              this.counts.mediumCount ?? 0,
              this.counts.lowCount ?? 0,
              this.counts.unknownCount ?? 0,
            ],
            backgroundColor: SEVERITY_CHART_COLORS,
            borderWidth: 0,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: 'bottom',
            labels: { color: '#9FA0A0', font: { size: 11 }, boxWidth: 12 },
          },
        },
      },
    });
  }
}
