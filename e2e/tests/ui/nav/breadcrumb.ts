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
 * The breadcrumb of the repository pages (`RepositoryBreadcrumbComponent`): `breadcrumb-item-<i>`,
 * 0-based from "Repositories"; every item but the last holds a `breadcrumb-link`, the last a
 * `breadcrumb-current`. Composed here (not in a shared page object) because only the navigation
 * specs use it.
 */
import { expect, type Locator, type Page } from '@playwright/test';

export class Breadcrumb {
  readonly root: Locator;

  constructor(private readonly page: Page) {
    this.root = page.getByTestId('breadcrumb');
  }

  item(index: number): Locator {
    return this.root.getByTestId(`breadcrumb-item-${index}`);
  }

  link(index: number): Locator {
    return this.item(index).getByTestId('breadcrumb-link');
  }

  get current(): Locator {
    return this.root.getByTestId('breadcrumb-current');
  }

  /** The crumbs' texts, in order (the link texts, then the current one). */
  async texts(): Promise<string[]> {
    const items = this.root.locator('[data-testid^="breadcrumb-item-"]');
    const texts = await items.allInnerTexts();
    return texts.map((text) => text.replace(/\s+/g, ' ').trim());
  }

  /** Exactly these crumbs are shown, and the last one is the non-link current item. */
  async expectCrumbs(expected: readonly string[]): Promise<void> {
    await expect(this.root).toBeVisible();
    await expect.poll(() => this.texts()).toEqual([...expected]);
    await expect(this.current).toHaveText(expected[expected.length - 1]);
    await expect(this.root.getByTestId('breadcrumb-link')).toHaveCount(expected.length - 1);
  }

  /** Clicks the link of crumb `index` and waits for the URL it points at. */
  async follow(index: number, path: string | RegExp): Promise<void> {
    await this.link(index).click();
    await expect(this.page).toHaveURL(
      typeof path === 'string' ? new RegExp(`${escapeRegExp(path)}(\\?.*)?$`) : path,
    );
  }
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
