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
 * The tabs a page opened, read from the browser itself (RPS-1315).
 *
 * A ctrl/middle click on a link makes Chromium open a BACKGROUND tab. Playwright learns about that tab
 * through the `page` event, and in headless Chromium the event is missing about every tenth to every
 * second time (and `context.pages()` misses the tab with it), so a test that waits for the event is
 * flaky. The browser's own list of targets (`Target.getTargets`, over a CDP session) always has the tab
 * once it exists, with the URL it is loading, so this asks that instead. (A tab opened by a click has no
 * `openerId`: it is opened without an opener relationship. It is found by its browser context instead:
 * every tab of the page's own context except the page itself.)
 */
import type { CDPSession, Page } from '@playwright/test';

export interface OpenedTab {
  targetId: string;
  url: string;
}

/** A watcher on the other tabs of `page`'s browser context, whether or not Playwright reported them. */
export class OpenedTabs {
  private constructor(
    private readonly session: CDPSession,
    private readonly self: string,
    private readonly browserContextId: string | undefined,
  ) {}

  static async of(page: Page): Promise<OpenedTabs> {
    const session = await page.context().newCDPSession(page);
    const { targetInfo } = await session.send('Target.getTargetInfo');
    return new OpenedTabs(session, targetInfo.targetId, targetInfo.browserContextId);
  }

  /** Every other tab of the page's context that is still open, with the URL it shows or is loading. */
  async list(): Promise<OpenedTab[]> {
    const { targetInfos } = await this.session.send('Target.getTargets');
    return targetInfos
      .filter(
        (target) =>
          target.type === 'page' &&
          target.targetId !== this.self &&
          target.browserContextId === this.browserContextId,
      )
      .map((target) => ({ targetId: target.targetId, url: target.url }));
  }

  /** The URLs of the other tabs (for `expect.poll`). */
  async urls(): Promise<string[]> {
    return (await this.list()).map((tab) => tab.url);
  }

  /** Closes every other tab, so the next assertion starts from none. */
  async closeAll(): Promise<void> {
    for (const tab of await this.list()) {
      await this.session.send('Target.closeTarget', { targetId: tab.targetId });
    }
  }

  async dispose(): Promise<void> {
    await this.session.detach().catch(() => undefined);
  }
}
