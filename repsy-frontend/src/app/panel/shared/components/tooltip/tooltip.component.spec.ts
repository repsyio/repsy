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

import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';

import { TooltipComponent } from './tooltip.component';

@Component({
  imports: [TooltipComponent],
  template: `
    @if (truncated) {
      <div style="width: 100px">
        <app-tooltip truncate [text]="text" [textHover]="text" />
      </div>
    } @else {
      <app-tooltip [text]="text.substring(0, 12) + '...'" [textHover]="text" />
    }
  `,
})
class HostComponent {
  truncated = true;
  text = 'a-very-long-package-name-that-does-not-fit-in-a-hundred-pixels';
}

describe('TooltipComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  const tooltip = () => fixture.debugElement.query(By.directive(TooltipComponent));
  const label = () => tooltip().nativeElement.querySelector('[data-testid="tooltip-text"]') as HTMLElement;
  const popup = () => tooltip().nativeElement.querySelector('[data-testid="tooltip-popup"]') as HTMLElement | null;

  const hover = () => {
    tooltip().triggerEventHandler('mouseenter');
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
  });

  describe('with truncate', () => {
    it('renders the full value and clips it with CSS instead of cutting the text', () => {
      fixture.detectChanges();

      expect(label().textContent).toBe(fixture.componentInstance.text);
      expect(label().classList).toContain('truncate');
      expect(tooltip().nativeElement.classList).toContain('min-w-0');
      expect(label().scrollWidth).toBeGreaterThan(label().clientWidth);
    });

    it('opens the popup with the full value when the text is clipped', () => {
      fixture.detectChanges();

      hover();

      expect(popup()?.textContent?.trim()).toBe(fixture.componentInstance.text);
    });

    it('does not open the popup for a value that fits', () => {
      fixture.componentInstance.text = 'short';
      fixture.detectChanges();

      hover();

      expect(popup()).toBeNull();
    });

    it('closes the popup when the pointer leaves', () => {
      fixture.detectChanges();
      hover();

      tooltip().triggerEventHandler('mouseleave');
      fixture.detectChanges();

      expect(popup()).toBeNull();
    });
  });

  describe('without truncate', () => {
    beforeEach(() => {
      fixture.componentInstance.truncated = false;
      fixture.detectChanges();
    });

    it('shows the given text as it is and does not truncate it with CSS', () => {
      expect(label().textContent).toBe('a-very-long-...');
      expect(label().classList).not.toContain('truncate');
      expect(tooltip().nativeElement.classList).not.toContain('min-w-0');
    });

    it('opens the popup with textHover when the text is longer than maxLength', () => {
      hover();

      expect(popup()?.textContent?.trim()).toBe(fixture.componentInstance.text);
    });
  });
});
