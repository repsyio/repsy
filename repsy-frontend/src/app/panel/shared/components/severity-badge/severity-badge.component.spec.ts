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
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Severity } from '../../../../../generated/api';
import { SeverityBadgeComponent } from './severity-badge.component';

describe('SeverityBadgeComponent', () => {
  let fixture: ComponentFixture<SeverityBadgeComponent>;
  let component: SeverityBadgeComponent;

  const text = (): string => (fixture.nativeElement as HTMLElement).textContent?.trim() ?? '';
  const badge = (): HTMLElement => (fixture.nativeElement as HTMLElement).querySelector('span') as HTMLElement;

  function render(inputs: Partial<SeverityBadgeComponent>): void {
    Object.assign(component, inputs);
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SeverityBadgeComponent] });
    fixture = TestBed.createComponent(SeverityBadgeComponent);
    component = fixture.componentInstance;
  });

  describe('a scanned badge', () => {
    it('shows the severity', () => {
      render({ severity: Severity.High, scanned: true });

      expect(text()).toBe('High');
      expect(component.isFirstScanInProgress).toBeFalse();
      expect(component.isFirstScanFailed).toBeFalse();
      expect(badge().getAttribute('title')).toBeNull();
    });

    it('shows Clean when the completed scans have no severity', () => {
      render({ severity: null, scanned: true, unscannedInProgressCount: 2 });

      expect(text()).toBe('Clean');
      expect(component.isClean).toBeTrue();
    });

    it('flags versions without a completed scan next to the severity', () => {
      render({ severity: Severity.Low, scanned: true, unscannedInProgressCount: 1, unscannedFailedCount: 2 });

      expect(text()).toBe('Low');
      expect(component.rescanInProgress).toBeTrue();
      expect(component.rescanTitle).toBe(
        '1 version being scanned for the first time and the first scan of 2 versions failed. Not included in the severity shown.',
      );
      expect(fixture.nativeElement.querySelector('[role="img"]')).not.toBeNull();
    });

    it('keeps the rescan note and adds the unscanned one', () => {
      render({ severity: Severity.Low, scanned: true, rescanFailedCount: 1, unscannedFailedCount: 1 });

      expect(component.rescanInProgress).toBeFalse();
      expect(component.rescanTitle).toBe(
        'The last rescan of 1 version failed. Showing the last completed scans. The first scan of 1 version failed. Not included in the severity shown.',
      );
    });
  });

  describe('a badge without a completed scan', () => {
    it('shows Scanning... while a first scan is unfinished', () => {
      render({ scanned: false, unscannedInProgressCount: 2 });

      expect(component.isFirstScanInProgress).toBeTrue();
      expect(component.isFirstScanFailed).toBeFalse();
      expect(text()).toBe('Scanning...');
      expect(badge().getAttribute('title')).toBe('2 versions being scanned for the first time.');
      expect(badge().querySelector('.animate-spin')).not.toBeNull();
      expect(badge().className).toContain('border-neutral-400');
      expect(fixture.nativeElement.querySelector('[role="img"]')).toBeNull();
    });

    it('shows Scan failed when every first scan failed', () => {
      render({ scanned: false, unscannedFailedCount: 1 });

      expect(component.isFirstScanInProgress).toBeFalse();
      expect(component.isFirstScanFailed).toBeTrue();
      expect(text()).toBe('Scan failed');
      expect(badge().getAttribute('title')).toBe('The first scan of 1 version failed.');
      expect(badge().querySelector('.animate-spin')).toBeNull();
      expect(badge().className).toContain('border-neutral-400');
      expect(badge().className).not.toContain('border-warning-600');
    });

    it('prefers Scanning... when some first scans are unfinished and others failed, and names both', () => {
      render({ scanned: false, unscannedInProgressCount: 1, unscannedFailedCount: 3 });

      expect(component.isFirstScanInProgress).toBeTrue();
      expect(component.isFirstScanFailed).toBeFalse();
      expect(text()).toBe('Scanning...');
      expect(badge().getAttribute('title')).toBe(
        '1 version being scanned for the first time and the first scan of 3 versions failed.',
      );
    });

    it('is not a first-scan state without unscanned versions', () => {
      render({ scanned: false, unscannedInProgressCount: 0, unscannedFailedCount: null });

      expect(component.isFirstScanInProgress).toBeFalse();
      expect(component.isFirstScanFailed).toBeFalse();
      expect(component.unscannedTitle).toBe('');
      expect(text()).toBe('Unknown');
    });
  });
});
