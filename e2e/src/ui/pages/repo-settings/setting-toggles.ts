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
 * The controls of `/:repo/settings` that save a repo setting the moment they are touched, as data (RPS-1618,
 * RPS-1619): the read-back matrix, the failed-save specs and the two-admins scenario all walk the same table, so a
 * new toggle is one row here and shows up in all three.
 *
 * Every row says which repo types show the control, how to flip it, what a successful flip stores, and what the
 * control has to show for a given stored state. "Shown equals stored" is the property the specs check after a
 * failed save (RPS-1618) and after a reload.
 */
import { expect, type Page } from '@playwright/test';

import { RepoType } from '../../../api/panel-api.js';
import type { RepoSettingsForm, RepoSettingsInfo } from '../../../api/panel-backend.js';
import { target } from '../../../target.js';
import { stubSupportedRepoTypes } from '../../security-stubs.js';
import { VulnerabilityScanningSection } from '../security.js';
import type { RepoSettingsPage } from './page.js';

export type SettingToggleName =
  'visibility' | 'override' | 'scanning' | 'allowance' | 'pgpVerifyAll' | 'pgpKeyServerLookup';

/** The settings a repo stores that the panel can change, as `GET /api/repos/{repo}/settings` answers them. */
export type StoredSettings = RepoSettingsInfo;

export interface SettingToggle {
  readonly name: SettingToggleName;
  /** For test titles. */
  readonly title: string;
  /** Whether the settings page of a repo of `type` shows this control at all. */
  appliesTo(type: RepoType): boolean;
  /** Whatever the page needs before it is opened: the scanning section only renders for a type that has a scanner. */
  prepare(page: Page, type: RepoType): Promise<void>;
  /**
   * Clicks the control twice in a row, faster than the server answers: a toggle is double-clicked, a selector (whose
   * second click opens a menu) has to be locked once its choice is on its way.
   */
  flipTwice(settings: RepoSettingsPage, type: RepoType): Promise<void>;
  /** Flips the control (the PUT is on its way when this returns). */
  flip(settings: RepoSettingsPage, type: RepoType): Promise<void>;
  /** The fields a successful flip changes, given what the repo stores before it. */
  flipped(stored: StoredSettings, type: RepoType): Partial<StoredSettings>;
  /** The success toast of a flip from `stored`. */
  successToast(stored: StoredSettings, type: RepoType): string;
  /** Asserts the control shows `stored` (retrying, so it also waits for a revert). */
  expectShows(settings: RepoSettingsPage, stored: StoredSettings, type: RepoType): Promise<void>;
}

/** The option of the Version Allowance selector a flip picks: the one the seeded pattern does NOT have. */
function allowanceOption(type: RepoType): string {
  return type === RepoType.NUGET ? 'pre-release' : 'snapshots';
}

function allowanceText(stored: StoredSettings, type: RepoType): RegExp {
  const nuget = type === RepoType.NUGET;
  if (stored.snapshots && !stored.releases) {
    return nuget ? /Pre-release/ : /Snapshots/;
  }
  if (!stored.snapshots && stored.releases) {
    return nuget ? /Stable/ : /Releases/;
  }
  return /All packages/i;
}

export const SETTING_TOGGLES: readonly SettingToggle[] = [
  {
    name: 'visibility',
    title: 'Visibility',
    appliesTo: () => true,
    prepare: async () => {},
    flipTwice: (settings) => settings.visibility.label.dblclick(),
    flip: (settings) => settings.visibility.flip(),
    flipped: (stored) => ({ privateRepo: !stored.privateRepo }),
    successToast: (stored) =>
      `Repository visibility has changed as ${stored.privateRepo ? 'public' : 'private'}`,
    // Checked = Public.
    expectShows: (settings, stored) => settings.visibility.expectChecked(!stored.privateRepo),
  },
  {
    name: 'override',
    title: 'Package Override',
    // Cargo and Go never replace a published version: the panel has no such section for them.
    appliesTo: (type) => type !== RepoType.CARGO && type !== RepoType.GOLANG,
    prepare: async () => {},
    flipTwice: (settings) => settings.packageOverride.label.dblclick(),
    flip: (settings) => settings.packageOverride.flip(),
    flipped: (stored) => ({ allowOverride: !stored.allowOverride }),
    successToast: (stored) =>
      `Package override is now ${stored.allowOverride ? 'blocked' : 'allowed'}`,
    expectShows: (settings, stored) =>
      settings.packageOverride.expectChecked(!!stored.allowOverride),
  },
  {
    name: 'scanning',
    title: 'Vulnerability Scanning',
    appliesTo: () => true,
    // The e2e default has no scanner, so the section is hidden: stub the gate, the settings stay real.
    prepare: async (page, type) => {
      await stubSupportedRepoTypes(page, [type]);
    },
    flipTwice: (settings) => new VulnerabilityScanningSection(settings.page).label.dblclick(),
    flip: (settings) => new VulnerabilityScanningSection(settings.page).flip(),
    flipped: (stored) => ({ securityScanEnabled: !stored.securityScanEnabled }),
    successToast: (stored) =>
      `Automatic security scanning is now ${stored.securityScanEnabled ? 'disabled' : 'enabled'}`,
    expectShows: (settings, stored) =>
      new VulnerabilityScanningSection(settings.page).expectChecked(!!stored.securityScanEnabled),
  },
  {
    name: 'allowance',
    title: 'Version Allowance',
    appliesTo: (type) => target.supportsVersionAllowanceSettings(type),
    prepare: async () => {},
    flipTwice: async (settings, type) => {
      await settings.allowance.choose(allowanceOption(type));
      await expect(settings.allowance.toggle).toBeDisabled();
    },
    flip: (settings, type) => settings.allowance.choose(allowanceOption(type)),
    flipped: () => ({ releases: false, snapshots: true }),
    successToast: (_stored, type) => `Version allowance has changed to ${allowanceOption(type)}`,
    expectShows: (settings, stored, type) =>
      expect(settings.allowance.toggle).toHaveText(allowanceText(stored, type)),
  },
  {
    // RPS-1628 (G16): PGP settings apply to Maven only (`rejectPgpSettingsForUnsupportedType`,
    // `RepoTxService`), which is also the one type every other row here already covers, so the N x N
    // matrices of `write-failures.spec.ts` (SET-11) and `concurrent-admins.spec.ts` (SET-13) exercise
    // it against every other setting for free.
    name: 'pgpVerifyAll',
    title: 'PGP Verify All Signatures',
    appliesTo: (type) => type === RepoType.MAVEN,
    prepare: async () => {},
    flipTwice: (settings) => settings.pgp.verifyAllLabel.dblclick(),
    flip: (settings) => settings.pgp.flipVerifyAll(),
    flipped: (stored) => ({
      pgpVerifyAllSignaturesEnabled: !stored.pgpVerifyAllSignaturesEnabled,
    }),
    successToast: (stored) =>
      `Every signature is ${stored.pgpVerifyAllSignaturesEnabled ? 'no longer' : 'now'} verified`,
    expectShows: (settings, stored) =>
      settings.pgp.expectVerifyAllChecked(!!stored.pgpVerifyAllSignaturesEnabled),
  },
  {
    name: 'pgpKeyServerLookup',
    title: 'PGP Key Server Lookup',
    appliesTo: (type) => type === RepoType.MAVEN,
    prepare: async () => {},
    flipTwice: (settings) => settings.pgp.keyServerLookupLabel.dblclick(),
    flip: (settings) => settings.pgp.flipKeyServerLookup(),
    flipped: (stored) => ({
      pgpKeyServerLookupEnabled: !stored.pgpKeyServerLookupEnabled,
    }),
    successToast: (stored) =>
      `Key server lookup is now ${stored.pgpKeyServerLookupEnabled ? 'disabled' : 'enabled'}`,
    expectShows: (settings, stored) =>
      settings.pgp.expectKeyServerLookupChecked(!!stored.pgpKeyServerLookupEnabled),
  },
];

/** The rows that show a control on the settings page of a `type` repo. */
export function togglesFor(type: RepoType): readonly SettingToggle[] {
  return SETTING_TOGGLES.filter((toggle) => toggle.appliesTo(type));
}

/**
 * A known, non-default value for every stored setting the panel can change, so that a save which carried a stale or
 * default value for another field shows up as a difference: private, override denied, scanning off, Maven and NuGet
 * releases only, and Maven's two PGP switches away from their defaults.
 */
export function seedPattern(type: RepoType): RepoSettingsForm {
  return {
    allowOverride: false,
    securityScanEnabled: false,
    ...(target.supportsVersionAllowanceSettings(type) ? { releases: true, snapshots: false } : {}),
    ...(type === RepoType.MAVEN
      ? { pgpVerifyAllSignaturesEnabled: true, pgpKeyServerLookupEnabled: false }
      : {}),
  };
}

/** What `GET .../settings` says, as the plain object the specs compare (`toEqual`). */
export function comparable(stored: StoredSettings): StoredSettings {
  return { ...stored };
}

/** Asserts the repo stores `seedPattern(type)` on top of the private repo the specs create, or the specs prove less than they say. */
export function expectSeeded(stored: StoredSettings, type: RepoType): void {
  expect(stored).toMatchObject({ privateRepo: true, securityScanEnabled: false });
  // Cargo and Go never replace a version: the override is not part of what they store (the GET omits it).
  if (type !== RepoType.CARGO && type !== RepoType.GOLANG) {
    expect(stored.allowOverride).toBe(false);
  }
}
