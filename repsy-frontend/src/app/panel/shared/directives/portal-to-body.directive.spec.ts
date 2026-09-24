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

import { PortalToBodyDirective } from './portal-to-body.directive';

@Component({
  standalone: true,
  imports: [PortalToBodyDirective],
  template: `
    <div
      id="row"
      role="button"
      tabindex="0"
      (click)="rowClicks = rowClicks + 1"
      (keydown.enter)="rowKeys = rowKeys + 1"
    >
      <button id="badge" type="button" (click)="open = true">badge</button>
      @if (open) {
        <div appPortalToBody id="dialog">
          <button id="close" type="button" (click)="open = false">{{ label }}</button>
        </div>
      }
    </div>
  `,
})
class HostComponent {
  public open = false;
  public label = 'close';
  public rowClicks = 0;
  public rowKeys = 0;
}

describe('PortalToBodyDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  const byId = (id: string): HTMLElement | null => document.getElementById(id);

  async function show(): Promise<void> {
    host.open = true;
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('moves the host to the end of the body, out of the row that declares it', async () => {
    await show();

    const dialog = byId('dialog');
    expect(dialog).not.toBeNull();
    expect(dialog.parentElement).toBe(document.body);
    expect(document.body.lastElementChild).toBe(dialog);
    expect(byId('row').contains(dialog)).toBeFalse();
  });

  it('keeps the moved content bound to its component', async () => {
    await show();

    host.label = 'Done';
    fixture.detectChanges();

    expect(byId('close').textContent).toBe('Done');
  });

  it('does not hand clicks and key presses made inside it to the row', async () => {
    await show();

    byId('close').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    byId('dialog').click();

    expect(host.rowKeys).toBe(0);
    expect(host.rowClicks).toBe(0);
  });

  it('still handles its own events, and is removed from the body when it goes away', async () => {
    await show();

    byId('close').click();
    fixture.detectChanges();

    expect(host.open).toBeFalse();
    expect(byId('dialog')).toBeNull();
  });

  it('is removed from the body when the declaring component is destroyed', async () => {
    await show();

    fixture.destroy();

    expect(byId('dialog')).toBeNull();
  });
});
