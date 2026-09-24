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

/**
 * The three sections of `/:repo/settings` that change a repo setting the moment they are touched:
 * Visibility and Package Override (a toggle) and Version Allowance (a selector). None of them asks
 * for a confirmation: each fires its `PUT /api/repos/{repo}/settings` straight from the click and
 * raises a toast, so a spec proves persistence through the API, not through what the control shows.
 */
import { expect, type Locator, type Page } from '@playwright/test';

/** A section built around one `app-toggle-component` and a hint text. */
abstract class ToggleSection {
  readonly root: Locator;
  readonly toggle: Locator;
  /** The `role="switch"` checkbox inside the toggle; click it, assert it with `toBeChecked()`. */
  readonly input: Locator;
  /** The text next to the switch (`Public`/`Private`, `Allow`/`Deny`). */
  readonly label: Locator;
  /** The dynamic help text under the switch. */
  readonly hint: Locator;

  protected constructor(page: Page, sectionId: string, toggleId: string, hintId: string) {
    this.root = page.getByTestId(sectionId);
    this.toggle = this.root.getByTestId(toggleId);
    this.input = this.toggle.getByTestId('toggle-input');
    this.label = this.toggle.getByTestId('toggle-label');
    this.hint = this.root.getByTestId(hintId);
  }

  /**
   * Flips the switch. The PUT is already on its way when this returns. The checkbox itself is
   * `sr-only` (covered by the drawn switch, so a click on it is refused as intercepted); the label
   * text is inside the same `<label>`, so clicking it activates the checkbox like a real user.
   */
  async flip(): Promise<void> {
    await this.label.click();
  }

  async expectChecked(checked: boolean): Promise<void> {
    if (checked) {
      await expect(this.input).toBeChecked();
    } else {
      await expect(this.input).not.toBeChecked();
    }
  }
}

/** Checked = Public (`privateRepo: false`), unchecked = Private. */
export class VisibilitySection extends ToggleSection {
  constructor(page: Page) {
    super(page, 'settings-visibility', 'settings-visibility-toggle', 'settings-visibility-hint');
  }
}

/** Checked = Allow (`allowOverride: true`), unchecked = Deny. Not rendered for Cargo and Go. */
export class PackageOverrideSection extends ToggleSection {
  constructor(page: Page) {
    super(page, 'settings-override', 'settings-override-toggle', 'settings-override-hint');
  }
}

/**
 * Maven (`all packages`/`snapshots`/`releases`) and NuGet (`all packages`/`pre-release`/`stable`).
 * The options are the raw values of the `RepoSupport` enum, which is what `selector-option-<value>`
 * carries; the selector button shows them capitalised.
 */
export class VersionAllowanceSection {
  readonly root: Locator;
  readonly selector: Locator;
  readonly toggle: Locator;
  readonly menu: Locator;
  readonly hint: Locator;

  constructor(private readonly page: Page) {
    this.root = page.getByTestId('settings-allowance');
    this.selector = this.root.getByTestId('settings-allowance-selector');
    this.toggle = this.selector.getByTestId('selector-toggle');
    this.menu = this.selector.getByTestId('selector-menu');
    this.hint = this.root.getByTestId('settings-allowance-hint');
  }

  option(value: string): Locator {
    return this.menu.getByTestId(`selector-option-${value}`);
  }

  /** Opens the menu and picks `value`; the PUT is already on its way when this returns. */
  async choose(value: string): Promise<void> {
    await this.toggle.click();
    await expect(this.menu).toBeVisible();
    await this.option(value).click();
    await expect(this.menu).toBeHidden();
  }

  /** The values the open menu offers, in order. */
  async options(): Promise<string[]> {
    await this.toggle.click();
    await expect(this.menu).toBeVisible();
    const ids = await this.menu
      .locator('[data-testid^="selector-option-"]')
      .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-testid') ?? ''));
    await this.toggle.click();
    await expect(this.menu).toBeHidden();
    return ids.map((id) => id.replace('selector-option-', ''));
  }
}
