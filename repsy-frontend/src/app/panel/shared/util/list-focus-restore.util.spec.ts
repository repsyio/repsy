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

import { fakeAsync, tick } from '@angular/core/testing';

import { restoreListFocus } from './list-focus-restore.util';

describe('restoreListFocus', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  afterEach(() => {
    container.remove();
    (document.activeElement as HTMLElement | null)?.blur();
  });

  function focusableButton(testId: string, parentTestId?: string): HTMLButtonElement {
    const button = document.createElement('button');
    button.setAttribute('data-testid', testId);
    if (parentTestId) {
      const wrapper = document.createElement('div');
      wrapper.setAttribute('data-testid', parentTestId);
      wrapper.appendChild(button);
      container.appendChild(wrapper);
    } else {
      container.appendChild(button);
    }
    return button;
  }

  it('does nothing when nothing was purposefully focused before the reload', fakeAsync(() => {
    focusableButton('dropdown-toggle', 'row-menu');
    document.body.focus();

    restoreListFocus(null, container);
    restoreListFocus(document.body, container);
    tick();

    expect(document.activeElement).toBe(document.body);
  }));

  it('does nothing when the focus survived the reload (the same control, still in the document)', fakeAsync(() => {
    const stillThere = focusableButton('pkg-refresh');
    stillThere.focus();

    restoreListFocus(stillThere, container);
    tick();

    expect(document.activeElement).toBe(stillThere);
  }));

  it('moves the focus to the first row menu when the previous target is gone and the focus fell to body', fakeAsync(() => {
    const toggle = focusableButton('dropdown-toggle', 'row-menu');
    const vanished = document.createElement('button');
    document.body.appendChild(vanished);
    vanished.focus();
    vanished.remove();
    expect(document.activeElement).toBe(document.body);

    restoreListFocus(vanished, container);
    tick();

    expect(document.activeElement).toBe(toggle);
  }));

  it('falls back to the pagination when there is no row menu', fakeAsync(() => {
    const pagination = document.createElement('nav');
    pagination.setAttribute('data-testid', 'pagination');
    const page = document.createElement('button');
    page.setAttribute('aria-disabled', 'true');
    const next = document.createElement('button');
    pagination.append(page, next);
    container.appendChild(pagination);

    const vanished = document.createElement('button');
    document.body.appendChild(vanished);
    vanished.focus();
    vanished.remove();

    restoreListFocus(vanished, container);
    tick();

    // The disabled page button is skipped; the next button is the first enabled candidate.
    expect(document.activeElement).toBe(next);
  }));

  it('falls back to any tabbable element when there is no row menu or pager', fakeAsync(() => {
    const link = document.createElement('a');
    link.href = '#';
    container.appendChild(link);

    const vanished = document.createElement('button');
    document.body.appendChild(vanished);
    vanished.focus();
    vanished.remove();

    restoreListFocus(vanished, container);
    tick();

    expect(document.activeElement).toBe(link);
  }));

  it('does not throw and does nothing without a container', fakeAsync(() => {
    const vanished = document.createElement('button');
    document.body.appendChild(vanished);
    vanished.focus();
    vanished.remove();

    expect(() => restoreListFocus(vanished, null)).not.toThrow();
    tick();

    expect(document.activeElement).toBe(document.body);
  }));

  it('skips a hidden match (display: none) and uses the next candidate', fakeAsync(() => {
    const hiddenToggle = focusableButton('dropdown-toggle', 'row-menu');
    hiddenToggle.style.display = 'none';
    const pagination = document.createElement('nav');
    pagination.setAttribute('data-testid', 'pagination');
    const next = document.createElement('button');
    pagination.appendChild(next);
    container.appendChild(pagination);

    const vanished = document.createElement('button');
    document.body.appendChild(vanished);
    vanished.focus();
    vanished.remove();

    restoreListFocus(vanished, container);
    tick();

    expect(document.activeElement).toBe(next);
  }));
});
