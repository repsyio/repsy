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
 * TOK-07 (RPS-1628 G20): the create-token modal's expiration date (`DeployTokenCreateModalComponent`)
 * against a browser whose timezone is not UTC. `minDate`/`maxDate`/the default value and the payload
 * `preparePayload()` builds are all computed with `moment.utc(...)`, never the browser's local zone, so
 * the claim under test is that this holds regardless of the viewer's zone, including at the moment the
 * viewer's own calendar date and the UTC calendar date disagree (the browser clock is frozen with
 * `page.clock` at 23:30 UTC so that is deterministic, not a wait for real midnight):
 *
 *  - Pacific/Kiritimati (UTC+14) is already on the NEXT calendar day when it is still today in UTC.
 *  - America/Los_Angeles (UTC-8 in March, before DST) is still on the PREVIOUS calendar day.
 *
 * If the bounds were ever computed from the browser's local `Date` instead (a regression a future
 * `moment.utc()` -> `moment()` edit could introduce without failing in a UTC CI runner), these two
 * zones would disagree with each other and with what is asserted here.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/fixtures.js';
import { RepoSettingsPage } from '../../../src/ui/pages/repo-settings/page.js';

const SETTINGS = '@settings';

const DAY_MS = 24 * 60 * 60 * 1000;

// 30 minutes before UTC midnight, so both zones below are on the "wrong" side of it. It is the end of the NEXT
// UTC day, never a fixed date: the server checks the date it is sent against its own, real clock (it has to
// be in the future, RPS-1995), so the frozen browser clock must not be in the past.
const frozenDay = Date.UTC(
  new Date().getUTCFullYear(),
  new Date().getUTCMonth(),
  new Date().getUTCDate() + 1,
);
const FROZEN_INSTANT = new Date(frozenDay + 23.5 * 60 * 60 * 1000).toISOString();
const utcDay = (offsetDays: number): string =>
  new Date(frozenDay + offsetDays * DAY_MS).toISOString().slice(0, 10);
const YESTERDAY_UTC = utcDay(-1);
const TODAY_UTC = utcDay(0);
const TOMORROW_UTC = utcDay(1);
// One calendar year ahead of the frozen day, as moment.utc().add(1, 'year') computes it.
const ONE_YEAR_LATER_UTC = (() => {
  const d = new Date(frozenDay);
  d.setUTCFullYear(d.getUTCFullYear() + 1);
  return d.toISOString().slice(0, 10);
})();
const ONE_DAY_PAST_MAX_UTC = new Date(Date.parse(ONE_YEAR_LATER_UTC) + DAY_MS)
  .toISOString()
  .slice(0, 10);
const RANGE_MESSAGE = 'Must be between tomorrow and one year from today';

const ZONES = ['Pacific/Kiritimati', 'America/Los_Angeles'] as const;

for (const timezoneId of ZONES) {
  test.describe(`Deploy tokens: expiry bounds in ${timezoneId}`, { tag: SETTINGS }, () => {
    test.use({ timezoneId });

    test(`TOK-07 the bounds and the default are the UTC tomorrow/+1-year, not the viewer's own "today" (${timezoneId})`, async ({
      adminPage,
      seeder,
    }) => {
      await adminPage.clock.setFixedTime(new Date(FROZEN_INSTANT));
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();

      const modal = await settings.tokens.openCreateModal();

      await expect(modal.expiration).toHaveAttribute('min', TOMORROW_UTC);
      await expect(modal.expiration).toHaveAttribute('max', ONE_YEAR_LATER_UTC);
      await expect(modal.expiration).toHaveValue(ONE_YEAR_LATER_UTC);
    });

    test(`TOK-07 the earliest allowed date is accepted, and what is stored is the same UTC day that was shown (${timezoneId})`, async ({
      adminPage,
      seeder,
      panelApi,
    }) => {
      await adminPage.clock.setFixedTime(new Date(FROZEN_INSTANT));
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();
      const { tokens } = settings;
      const name = `tok-min-${timezoneId.split('/')[1]}`;

      await tokens.openCreateModal();
      await tokens.createModal.create({ name, expirationDate: TOMORROW_UTC });

      await settings.shell.toasts.expectSuccess('Deploy token created successfully.');
      await tokens.infoModal.close();

      const stored = (await panelApi.listDeployTokens(repo.name)).find((t) => t.name === name);
      expect(stored?.expirationDate).toBeTruthy();
      // `preparePayload()` builds the instant from the UTC calendar date typed, not the zone the
      // browser (or the server, which is always UTC) happens to run in: it round-trips to the same day.
      expect(stored!.expirationDate!.slice(0, 10)).toBe(TOMORROW_UTC);
    });

    test(`TOK-07 today (UTC) and one day past the maximum are both rejected, with the same message (${timezoneId})`, async ({
      adminPage,
      seeder,
    }) => {
      await adminPage.clock.setFixedTime(new Date(FROZEN_INSTANT));
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const settings = new RepoSettingsPage(adminPage, repo.name);
      await settings.goto();
      const modal = await settings.tokens.openCreateModal();
      // A name, so the only thing keeping Create disabled below is the expiration date itself.
      await modal.name.fill(`tok-range-${timezoneId.split('/')[1]}`);

      // Today, in UTC, is one day short of the minimum.
      await modal.expiration.fill(TODAY_UTC);
      await modal.expiration.blur();
      await expect(modal.expirationError('range')).toHaveText(`• ${RANGE_MESSAGE}`);
      await expect(modal.submitButton).toBeDisabled();

      // A day further back is rejected the same way.
      await modal.expiration.fill(YESTERDAY_UTC);
      await modal.expiration.blur();
      await expect(modal.expirationError('range')).toHaveText(`• ${RANGE_MESSAGE}`);
      await expect(modal.submitButton).toBeDisabled();

      // One day past the maximum, symmetrically.
      await modal.expiration.fill(ONE_DAY_PAST_MAX_UTC);
      await modal.expiration.blur();
      await expect(modal.expirationError('range')).toHaveText(`• ${RANGE_MESSAGE}`);
      await expect(modal.submitButton).toBeDisabled();

      // Back inside the range clears the error and re-enables the form.
      await modal.expiration.fill(TOMORROW_UTC);
      await modal.expiration.blur();
      await expect(modal.expirationError('range')).toHaveCount(0);
      await expect(modal.submitButton).toBeEnabled();
    });
  });
}
