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

import { renderComponent } from '../../../pages/repository/testing/render-spec-helpers';
import { SelectorComponent } from './selector.component';

describe('SelectorComponent', () => {
  const OPTIONS = ['all packages', 'snapshots', 'releases'];

  it('opens its menu and reports the chosen option', async () => {
    const { el, fixture } = await renderComponent(SelectorComponent, [], {
      options: OPTIONS,
      selectedOption: 'all packages',
    });
    const chosen: string[] = [];
    fixture.componentInstance.choose.subscribe((option) => chosen.push(option));

    el.querySelector<HTMLButtonElement>('[data-testid="selector-toggle"]').click();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('[data-testid="selector-option-snapshots"]').click();

    expect(chosen).toEqual(['snapshots']);
    expect(fixture.componentInstance.selectedOption).toBe('snapshots');
  });

  it('does not open while it is disabled (RPS-1618)', async () => {
    const { el, fixture } = await renderComponent(SelectorComponent, [], {
      options: OPTIONS,
      selectedOption: 'all packages',
      disabled: true,
    });
    const button = el.querySelector<HTMLButtonElement>('[data-testid="selector-toggle"]');

    button.click();
    fixture.detectChanges();

    expect(button.disabled).toBeTrue();
    expect(el.querySelector('[data-testid="selector-menu"]')).toBeNull();
  });
});
