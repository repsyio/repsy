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

import { DialogDirective } from './dialog.directive';

@Component({
  standalone: true,
  imports: [DialogDirective],
  template: `
    <button id="opener" type="button" (click)="open = true">open</button>
    @if (open) {
      <div
        id="outer"
        [appDialog]="role"
        #dialog="appDialog"
        [appDialogClosable]="closable"
        (appDialogClose)="closes.push('outer')"
      >
        <h2 [id]="dialog.titleId">Title</h2>
        <input id="outer-first" data-dialog-focus />
        <button id="outer-hidden" type="button" hidden>hidden</button>
        <button id="outer-off" type="button" disabled>off</button>
        <button id="outer-last" type="button" (click)="nested = true">last</button>
      </div>
    }
    @if (nested) {
      <div id="inner" appDialog (appDialogClose)="closes.push('inner')">
        <button id="inner-a" type="button">a</button>
        <button id="inner-b" type="button">b</button>
      </div>
    }
  `,
})
class HostComponent {
  public open = false;
  public nested = false;
  public closable = true;
  public role = '';
  public closes: string[] = [];
}

describe('DialogDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  const byId = (id: string): HTMLElement => document.getElementById(id) as HTMLElement;

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function openDialog(): Promise<void> {
    byId('opener').focus();
    host.open = true;
    await render();
  }

  function press(key: string, shiftKey = false): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true });
    document.activeElement.dispatchEvent(event);
    return event;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => fixture.nativeElement.remove());

  it('is a modal dialog named by its title', async () => {
    await openDialog();

    const dialog = byId('outer');
    expect(dialog.getAttribute('role')).toBe('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    const titleId = dialog.getAttribute('aria-labelledby');
    expect(titleId).toBeTruthy();
    expect(document.getElementById(titleId).textContent).toBe('Title');
  });

  it('is an alertdialog when asked to be', async () => {
    host.role = 'alertdialog';
    await openDialog();

    expect(byId('outer').getAttribute('role')).toBe('alertdialog');
  });

  it('gives every dialog its own title id', async () => {
    host.nested = true;
    await openDialog();

    expect(byId('outer').getAttribute('aria-labelledby')).not.toBe(byId('inner').getAttribute('aria-labelledby'));
  });

  it('moves focus to the marked element when it opens', async () => {
    await openDialog();

    expect(document.activeElement).toBe(byId('outer-first'));
  });

  it('focuses the dialog itself when nothing is marked', async () => {
    host.nested = true;
    await render();

    expect(document.activeElement).toBe(byId('inner'));
  });

  it('returns focus to the opener when it closes', async () => {
    await openDialog();
    expect(document.activeElement).toBe(byId('outer-first'));

    host.open = false;
    await render();

    expect(document.activeElement).toBe(byId('opener'));
  });

  it('keeps Tab inside: from the last control to the first, skipping hidden and disabled ones', async () => {
    await openDialog();
    byId('outer-last').focus();

    const event = press('Tab');

    expect(event.defaultPrevented).toBeTrue();
    expect(document.activeElement).toBe(byId('outer-first'));
  });

  it('keeps Shift+Tab inside: from the first control to the last', async () => {
    await openDialog();
    byId('outer-first').focus();

    const event = press('Tab', true);

    expect(event.defaultPrevented).toBeTrue();
    expect(document.activeElement).toBe(byId('outer-last'));
  });

  it('leaves Tab between two controls inside to the browser', async () => {
    await openDialog();
    byId('outer-first').focus();

    expect(press('Tab').defaultPrevented).toBeFalse();
  });

  it('pulls focus that sits outside back in on Tab', async () => {
    await openDialog();
    byId('opener').focus();

    press('Tab');

    expect(document.activeElement).toBe(byId('outer-first'));
  });

  it('asks to close on Escape', async () => {
    await openDialog();

    const event = press('Escape');

    expect(host.closes).toEqual(['outer']);
    expect(event.defaultPrevented).toBeTrue();
  });

  it('ignores Escape while it is not closable (a request is in flight)', async () => {
    host.closable = false;
    await openDialog();

    press('Escape');

    expect(host.closes).toEqual([]);
  });

  it('leaves an Escape that something inside already handled (an open menu) alone', async () => {
    await openDialog();
    byId('outer-first').addEventListener('keydown', (event) => event.preventDefault());

    press('Escape');

    expect(host.closes).toEqual([]);
  });

  it('hands the keyboard to a dialog opened from it, and back when that one closes', async () => {
    await openDialog();
    byId('outer-last').focus();
    byId('outer-last').click();
    await render();
    expect(document.activeElement).toBe(byId('inner'));

    byId('inner-b').focus();
    press('Tab');
    expect(document.activeElement).toBe(byId('inner-a'));
    press('Escape');
    expect(host.closes).toEqual(['inner']);

    host.nested = false;
    await render();
    expect(document.activeElement).toBe(byId('outer-last'));
    press('Escape');
    expect(host.closes).toEqual(['inner', 'outer']);
  });
});
