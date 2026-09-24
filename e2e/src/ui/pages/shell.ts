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
 * The panel shell: the layout every logged-in page shares (sidebar, header and its avatar menu, the
 * toast stack, the danger modal). Page objects of a page compose a `Shell` rather than repeating it.
 */
import { expect, type Locator, type Page } from '@playwright/test';

import { DangerModal, Toasts } from './components.js';

export type SidebarLink = 'dashboard' | 'repositories' | 'users' | 'security';

export class Shell {
  readonly toasts: Toasts;
  readonly dangerModal: DangerModal;

  /**
   * The desktop sidebar (`sidebar-*`), always in the DOM at the project's viewport; it is `display:
   * none` below `md`, use `mobileSidebar` there. The Users and Security links only exist for an admin
   * (and Security only when a scanner is configured).
   */
  readonly sidebar: {
    root: Locator;
    dashboard: Locator;
    repositories: Locator;
    users: Locator;
    security: Locator;
    logout: Locator;
  };

  /** The mobile sidebar (`mobile-sidebar-*`); it is only rendered while open, see `header.burger`. */
  readonly mobileSidebar: {
    root: Locator;
    close: Locator;
    link: (name: SidebarLink) => Locator;
    logout: Locator;
  };

  /**
   * The header; `menu` and its items only exist while the avatar menu is open (`openAvatarMenu`). Without a
   * session (RPS-1306) there is no `avatar` and no menu: the header shows the `login` link instead.
   */
  readonly header: {
    root: Locator;
    burger: Locator;
    login: Locator;
    avatar: Locator;
    menu: Locator;
    profile: Locator;
    logout: Locator;
  };

  constructor(readonly page: Page) {
    this.toasts = new Toasts(page);
    this.dangerModal = new DangerModal(page);

    this.sidebar = {
      root: page.getByTestId('sidebar'),
      dashboard: page.getByTestId('sidebar-link-dashboard'),
      repositories: page.getByTestId('sidebar-link-repositories'),
      users: page.getByTestId('sidebar-link-users'),
      security: page.getByTestId('sidebar-link-security'),
      logout: page.getByTestId('sidebar-logout'),
    };
    this.mobileSidebar = {
      root: page.getByTestId('mobile-sidebar'),
      close: page.getByTestId('mobile-sidebar-close'),
      link: (name) => page.getByTestId(`mobile-sidebar-link-${name}`),
      logout: page.getByTestId('mobile-sidebar-logout'),
    };
    this.header = {
      root: page.getByTestId('header'),
      burger: page.getByTestId('header-burger'),
      login: page.getByTestId('header-login'),
      avatar: page.getByTestId('header-avatar'),
      menu: page.getByTestId('header-menu'),
      profile: page.getByTestId('header-menu-profile'),
      logout: page.getByTestId('header-menu-logout'),
    };
  }

  /** Opens the header's avatar menu and waits for it. */
  async openAvatarMenu(): Promise<void> {
    await this.header.avatar.click();
    await expect(this.header.menu).toBeVisible();
  }

  /** Logs out through the desktop sidebar and waits for the login page. */
  async logoutViaSidebar(): Promise<void> {
    await this.sidebar.logout.click();
    await this.expectOnLogin();
  }

  /** Logs out through the header's avatar menu and waits for the login page. */
  async logoutViaHeader(): Promise<void> {
    await this.openAvatarMenu();
    await this.header.logout.click();
    await this.expectOnLogin();
  }

  private async expectOnLogin(): Promise<void> {
    await expect(this.page).toHaveURL(/\/login(\?.*)?$/);
  }

  /**
   * Runs `action` and resolves with its result once the first response whose URL matches `url` has
   * arrived: the call that drives a view. Prefer this (or an element assertion) over any fixed wait:
   * "the click returned" says nothing about the view.
   */
  async waitForView<T>(url: string | RegExp, action: () => Promise<T>): Promise<T> {
    const response = this.page.waitForResponse(url);
    const result = await action();
    await response;
    return result;
  }
}
