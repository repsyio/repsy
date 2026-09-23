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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Chart } from 'chart.js';

import { SeverityBreakdownComponent, SeverityCounts } from './severity-breakdown.component';

/** Lets the `setTimeout` the component uses to wait for its canvas run. */
function nextTask(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve));
}

describe('SeverityBreakdownComponent', () => {
  let fixture: ComponentFixture<SeverityBreakdownComponent>;
  let component: SeverityBreakdownComponent;

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const canvas = (): HTMLCanvasElement | null => element().querySelector('canvas');

  function show(counts: SeverityCounts | null): void {
    fixture.componentRef.setInput('counts', counts);
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SeverityBreakdownComponent] });
    fixture = TestBed.createComponent(SeverityBreakdownComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => fixture.destroy());

  describe('hasFindings', () => {
    it('is false without counts, and for a total of zero', () => {
      expect(component.hasFindings).toBeFalse();

      component.counts = { totalCount: 0 };
      expect(component.hasFindings).toBeFalse();

      component.counts = {};
      expect(component.hasFindings).toBeFalse();
    });

    it('is true for any positive total', () => {
      component.counts = { totalCount: 1 };

      expect(component.hasFindings).toBeTrue();
    });
  });

  describe('without findings', () => {
    it('says that no vulnerabilities are known, and draws nothing', async () => {
      show({ totalCount: 0 });
      await nextTask();

      expect(element().textContent).toContain('No known vulnerabilities found.');
      expect(canvas()).toBeNull();
      expect(element().querySelector('app-severity-badge')).toBeNull();
    });

    it('does the same while there are no counts at all', () => {
      show(null);

      expect(element().textContent).toContain('No known vulnerabilities found.');
    });
  });

  describe('with findings', () => {
    const counts: SeverityCounts = {
      totalCount: 6,
      criticalCount: 1,
      highCount: 0,
      mediumCount: 2,
      lowCount: 3,
      unknownCount: 0,
    };

    it('shows a badge only for the severities that have findings', () => {
      show(counts);

      const badges = Array.from(element().querySelectorAll('app-severity-badge')).map((badge) =>
        badge.textContent?.replace(/\s+/g, ' ').trim(),
      );
      expect(badges.length).toBe(3);
      expect(badges[0]).toContain('1');
      expect(badges[1]).toContain('2');
      expect(badges[2]).toContain('3');
    });

    it('draws a doughnut chart of the five counts, in severity order', async () => {
      show(counts);
      await nextTask();

      const chart = Chart.getChart(canvas() as HTMLCanvasElement);
      expect((chart?.config as { type?: string }).type).toBe('doughnut');
      expect(chart?.data.labels).toEqual(['Critical', 'High', 'Medium', 'Low', 'Unknown']);
      expect(chart?.data.datasets[0].data).toEqual([1, 0, 2, 3, 0]);
    });

    it('counts a missing severity as zero', async () => {
      show({ totalCount: 2, highCount: 2 });
      await nextTask();

      expect(Chart.getChart(canvas() as HTMLCanvasElement)?.data.datasets[0].data).toEqual([0, 2, 0, 0, 0]);
    });

    it('redraws the chart when the counts change', async () => {
      show(counts);
      await nextTask();

      show({ totalCount: 4, unknownCount: 4 });
      await nextTask();

      expect(Chart.getChart(canvas() as HTMLCanvasElement)?.data.datasets[0].data).toEqual([0, 0, 0, 0, 4]);
    });

    it('destroys the chart with the component', async () => {
      show(counts);
      await nextTask();
      const chartCanvas = canvas() as HTMLCanvasElement;
      expect(Chart.getChart(chartCanvas)).toBeDefined();

      component.ngOnDestroy();

      expect(Chart.getChart(chartCanvas)).toBeUndefined();
    });

    it('draws nothing when the canvas is gone by the time the timer fires', async () => {
      show(counts);
      component.chartCanvasRef = undefined;

      await nextTask();

      expect(Chart.getChart(canvas() as HTMLCanvasElement)).toBeUndefined();
    });
  });

  it('ngOnDestroy is safe when nothing was ever drawn', () => {
    expect(() => component.ngOnDestroy()).not.toThrow();
  });
});
