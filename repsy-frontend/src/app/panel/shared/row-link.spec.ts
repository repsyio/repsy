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
import { provideRouter, RouterLink } from '@angular/router';

import { DropdownComponent } from './components/dropdown/dropdown.component';
import { TooltipComponent } from './components/tooltip/tooltip.component';

/**
 * The list-row pattern of RPS-1266 (`.row-link-host` in styles.css): one real link stretched over the
 * row, everything else interactive in the row a sibling above it. This spec runs with the real global
 * styles, so it checks the geometry the pattern depends on.
 *
 * The geometry is read with `elementFromPoint`, which answers null for a point outside the viewport. The
 * Karma page is a small iframe whose body also holds the jasmine reporter, and that reporter grows with every
 * spec that has run, so a host appended to the end of the body ended up below the visible area for some
 * random orders (RPS-1319: `innerHeight` 437, host at y=461, page scrolled). The host therefore sits in its
 * own fixed container in the top-left corner, above everything else, so neither the body's contents nor the
 * scroll position can move it out of the viewport or cover it.
 */
@Component({
  imports: [RouterLink, DropdownComponent, TooltipComponent],
  template: `
    <div data-testid="rows" style="width: 600px">
      @for (name of ['first', 'second']; track name) {
        <div [attr.data-testid]="'row-' + name">
          <div class="row-link-host flex items-center gap-4 p-4">
            <a class="row-link" [routerLink]="'/' + name" [attr.aria-label]="name"></a>
            <span data-testid="plain" class="w-40">plain text</span>
            <app-tooltip data-testid="tip" class="w-40" [text]="name" [textHover]="name" />
            <a data-testid="side" [routerLink]="'/side-' + name">side link</a>
            <app-dropdown data-testid="menu"><button data-testid="item" type="button">Item</button></app-dropdown>
          </div>
        </div>
      }
    </div>
  `,
})
class RowsHostComponent {}

describe('list row with a stretched link', () => {
  let fixture: ComponentFixture<RowsHostComponent>;
  let viewport: HTMLElement;
  let root: HTMLElement;

  const q = <T extends HTMLElement = HTMLElement>(row: string, selector: string) =>
    root.querySelector<T>(`[data-testid="row-${row}"] ${selector}`)!;

  const centre = (element: HTMLElement) => {
    const box = element.getBoundingClientRect();
    return document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [RowsHostComponent], providers: [provideRouter([])] });
    viewport = document.createElement('div');
    viewport.setAttribute('data-testid', 'row-link-viewport');
    viewport.style.cssText = 'position: fixed; top: 0; left: 0; width: 700px; z-index: 2147483000; background: white;';
    document.body.appendChild(viewport);
    fixture = TestBed.createComponent(RowsHostComponent);
    viewport.appendChild(fixture.nativeElement);
    root = fixture.nativeElement;
    fixture.detectChanges();
    await fixture.whenStable();
    // Let the layout settle (fonts, the global styles) before any geometry is read.
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  });

  afterEach(() => {
    fixture.destroy();
    viewport.remove();
  });

  it('stretches the link over the whole row, named after the row', () => {
    const link = q<HTMLAnchorElement>('first', '.row-link');
    const host = q('first', '.row-link-host');

    expect(link.getAttribute('aria-label')).toBe('first');
    expect(link.getAttribute('href')).toBe('/first');
    expect(link.getBoundingClientRect().width).toBe(host.getBoundingClientRect().width);
    expect(link.getBoundingClientRect().height).toBe(host.getBoundingClientRect().height);
  });

  it('takes the click on plain text and on empty row space', () => {
    expect(centre(q('first', '[data-testid="plain"]'))).toBe(q('first', '.row-link'));
    const host = q('first', '.row-link-host').getBoundingClientRect();
    expect(document.elementFromPoint(host.x + host.width - 4, host.y + 4)).toBe(q('first', '.row-link'));
  });

  it('keeps the secondary link, the tooltip and the menu toggle above the row link', () => {
    expect(centre(q('first', '[data-testid="side"]'))).toBe(q('first', '[data-testid="side"]'));
    expect(q('first', '[data-testid="tip"]').contains(centre(q('first', '[data-testid="tip"]')))).toBeTrue();
    const toggle = q('first', '[data-testid="dropdown-toggle"]');
    expect(toggle.contains(centre(toggle))).toBeTrue();
  });

  it('has no interactive element inside another', () => {
    const nested = root.querySelectorAll('a a, a button, button a, button button, [role="button"]');

    expect(nested.length).toBe(0);
    expect(q('first', '.row-link').children.length).toBe(0);
  });

  it('paints the open menu of a row over the next row', () => {
    q<HTMLButtonElement>('first', '[data-testid="dropdown-toggle"]').click();
    fixture.detectChanges();
    const item = q('first', '[data-testid="item"]');

    expect(item).not.toBeNull();
    expect(item.contains(centre(item))).toBeTrue();
  });
});
