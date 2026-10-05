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

/**
 * RPS-1761, time zones (the OS half; the Cloud half is repsy-mono #1695). Angular's `date` pipe formats in the
 * browser's zone, so the same stored instant must read differently in two zones 25 hours apart. Two dates are
 * under test: the Last Login popup of the Users page (`user.lastLoginAt | date: 'medium'`) and the crate version's
 * "Uploaded at:" line. The expected text is built from the instant the panel API returned for that very page,
 * formatted with `Intl` for the zone under test.
 *
 * A timestamp the API sent without a zone designator would be read by the pipe as local wall-clock time and print
 * the same text in both zones; the specs then assert that instead, so the finding is visible rather than hidden.
 */
import type { Response } from '@playwright/test';

import { RepoType } from '../../../src/api/panel-api.js';
import { publishCrate } from '../../../src/seed/packages/cargo.js';
import { loginSession } from '../../../src/ui/session.js';
import { expect, test } from '../../../src/ui/users-fixtures.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const ZONES = ['Pacific/Kiritimati', 'Pacific/Pago_Pago'] as const; // UTC+14 and UTC-11

/** Collapses every kind of space (the ICU narrow no-break space before AM/PM included) to one plain space. */
function squash(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

const DESIGNATOR = /(?:Z|[+-]\d\d:?\d\d)$/;

function isZoneAware(instant: string | number): boolean {
  return typeof instant === 'number' || DESIGNATOR.test(instant);
}

/** What `date: 'medium'` prints for `instant` in `timeZone` (en-US). */
function medium(instant: string | number, timeZone: string): string {
  const aware = isZoneAware(instant);
  // without a designator Angular keeps the wall-clock digits, whatever the zone
  const date = new Date(aware ? instant : `${instant}Z`);
  return squash(
    new Intl.DateTimeFormat('en-US', {
      dateStyle: 'medium',
      timeStyle: 'medium',
      timeZone: aware ? timeZone : 'UTC',
    }).format(date),
  );
}

function findCreatedAt(value: unknown): string | number | undefined {
  if (value === null || typeof value !== 'object') {
    return undefined;
  }
  const record = value as Record<string, unknown>;
  const own = record['created_at'] ?? record['createdAt'];
  if (typeof own === 'string' || typeof own === 'number') {
    return own;
  }
  for (const child of Object.values(record)) {
    const found = findCreatedAt(child);
    if (found !== undefined) {
      return found;
    }
  }
  return undefined;
}

/** The two zones are 25 hours apart: a zone-aware instant must read differently, an unzoned one the same. */
function expectZonesTellApart(instant: string | number): void {
  const [first, second] = ZONES.map((zone) => medium(instant, zone));
  if (isZoneAware(instant)) {
    expect(first).not.toBe(second);
  } else {
    expect(first).toBe(second);
  }
}

test.use({ locale: 'en-US' });

// @cloud-skip: Repsy Cloud has its own copy of the crate half (repsy-cloud/e2e/ui-cloud/edge-cases/timezone.spec.ts,
// #1695) and no Users page.
for (const timeZone of ZONES) {
  test.describe(`RPS-1761 dates in ${timeZone}`, { tag: ['@cloud-skip'] }, () => {
    test.use({ timezoneId: timeZone });

    test('the Users page Last Login popup is the stored instant in the zone of the browser', async ({
      usersPage,
      seededUser,
      panelApi,
    }) => {
      await loginSession(seededUser.username, seededUser.password);
      // The server stamps lastLoginAt from an @Async login listener, so it can land a moment after the
      // login answered (a flake of this spec on origin/main, RPS-1961): poll for it.
      let lastLoginAt: string | number | undefined;
      await expect
        .poll(
          async () => {
            const stored = (await panelApi.listUsers({ q: seededUser.username })).find(
              (user) => user.username === seededUser.username,
            );
            lastLoginAt = stored?.lastLoginAt as unknown as string | number | undefined;
            return Boolean(lastLoginAt);
          },
          { message: 'the server recorded the login' },
        )
        .toBe(true);

      await usersPage.goto();
      await usersPage.search(seededUser.username);
      const cell = usersPage.list.inRow(seededUser.username, 'row-last-login');
      await cell.hover();
      await expect(async () => {
        const text = await cell.getByTestId('tooltip-popup').innerText();
        expect(squash(text)).toBe(medium(lastLoginAt as string | number, timeZone));
      }).toPass();
      expectZonesTellApart(lastLoginAt as string | number);
    });

    test('the crate version "Uploaded at" line is the stored instant in the zone of the browser', async ({
      adminPage,
      seeder,
    }) => {
      const repo = await seeder.createRepo(RepoType.CARGO);
      const crateName = `e2e_${seeder.runId}_tz`;
      const pkg = await publishCrate(repo.name, crateName, '1.0.0');

      const bodies: Promise<unknown>[] = [];
      adminPage.on('response', (res: Response) => {
        if (
          res.request().method() === 'GET' &&
          res.url().includes('/api/') &&
          res.url().includes(crateName) &&
          res.ok()
        ) {
          bodies.push(res.json().catch(() => undefined));
        }
      });
      const detail = protocolPages(adminPage, DESCRIPTORS.cargo, repo.name).detail(pkg);
      await detail.goto();
      const line = detail.byId('pkg-detail-published');
      await expect(line).toContainText('Uploaded at:');

      const createdAt = (await Promise.all(bodies)).map(findCreatedAt).find((v) => v !== undefined);
      expect(createdAt, 'the page loaded a created_at').toBeDefined();

      const text = squash((await line.textContent()) ?? '');
      const printed = text.replace(/^Uploaded at:\s*/, '').replace(/\s*\(.*$/, '');
      expect(printed).toBe(medium(createdAt as string | number, timeZone));
      expectZonesTellApart(createdAt as string | number);
    });
  });
}
