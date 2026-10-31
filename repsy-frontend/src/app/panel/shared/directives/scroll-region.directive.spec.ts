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

import { ScrollRegionDirective } from './scroll-region.directive';

@Component({
  standalone: true,
  imports: [ScrollRegionDirective],
  template: `
    <div id="box" appScrollRegion="Snippet" style="width: 100px; height: 40px; overflow: auto">
      <div [style.width.px]="contentWidth" [style.height.px]="contentHeight"></div>
    </div>
  `,
})
class HostComponent {
  contentWidth = 50;
  contentHeight = 20;
}

describe('ScrollRegionDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let box: HTMLElement;

  const settle = async (): Promise<void> => {
    fixture.detectChanges();
    await fixture.whenStable();
    // ResizeObserver and MutationObserver callbacks run after the layout / microtask checkpoint.
    await new Promise<void>((resolve) => setTimeout(resolve, 50));
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    box = fixture.nativeElement.querySelector('#box');
  });

  afterEach(() => fixture.nativeElement.remove());

  it('stays a plain element while its content fits', async () => {
    await settle();
    expect(box.getAttribute('role')).toBeNull();
    expect(box.getAttribute('tabindex')).toBeNull();
    expect(box.getAttribute('aria-label')).toBeNull();
  });

  it('becomes a labelled, focusable region when the content overflows horizontally', async () => {
    host.contentWidth = 400;
    await settle();
    expect(box.getAttribute('role')).toBe('region');
    expect(box.getAttribute('tabindex')).toBe('0');
    expect(box.getAttribute('aria-label')).toBe('Snippet');
  });

  it('becomes a region when the content overflows vertically', async () => {
    host.contentHeight = 300;
    await settle();
    expect(box.getAttribute('role')).toBe('region');
    expect(box.getAttribute('tabindex')).toBe('0');
  });

  it('drops the region again when the content shrinks back', async () => {
    host.contentWidth = 400;
    await settle();
    host.contentWidth = 50;
    await settle();
    expect(box.getAttribute('role')).toBeNull();
    expect(box.getAttribute('tabindex')).toBeNull();
  });
});
