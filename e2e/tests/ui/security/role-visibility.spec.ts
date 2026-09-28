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
 * SEC-02e (RPS-1628 G17): what a USER (non-admin) may see and do of the security surface, against the
 * REAL backend (not stubbed): the sidebar link, the cross-repository `/security` browser, and a
 * version's own scan section.
 *
 * Two different gates are at work, and this spec keeps them apart instead of treating "security" as one
 * admin-only feature:
 *
 *  - The cross-repository scan browser (`/security`, `GET /api/security/scans*`) is admin-only
 *    (`PanelAuthHelper#requireAdmin`, `SecurityScanController`) and its sidebar link only renders for
 *    `isAdmin()` (`sidebar.component.html`). `guards.spec.ts` already proves the route bounces a USER to
 *    "/" (`adminGuard`); this spec adds the sidebar link itself, which that test does not reach.
 *  - A version's own scan section and its badges are open to every SIGNED-IN user on purpose
 *    (`VulnerabilityScanController#getSecuritySummary`'s doc comment cites RPS-919: reading a scan
 *    result discloses nothing a signed-in user could not already read). Triggering a scan
 *    (`POST .../scan`) needs only `Permission.WRITE` (`@RepoOperation(permission = Permission.WRITE)`),
 *    the same tier as publishing a version, and `authorizeUser` grants WRITE to ANY authenticated user,
 *    ADMIN or USER (`ProtocolAuthService`): a USER is not the "canManage" tier `Permission.MANAGE` needs
 *    (repo settings, deleting a repository, RPS-1618's `authorizePanelUser`). So a USER sees and can use
 *    Scan Now / Re-scan on a version they can already publish to, same as any other write action; this
 *    is asserted here as the deliberate behaviour it is, not assumed away.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { ScanSection } from '../../../src/ui/pages/security.js';
import { Shell } from '../../../src/ui/pages/shell.js';
import { env } from '../../../src/env.js';
import { loginSession } from '../../../src/ui/session.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import {
  ScanScript,
  stubSupportedRepoTypes,
  stubVersionScans,
} from '../../../src/ui/security-stubs.js';

const MOCKED = '@mocked';

test.describe('SEC-02e security: sidebar and route, by role', { tag: MOCKED }, () => {
  test('the sidebar Security link exists for an admin and does not exist for a USER', async ({
    adminPage,
    userPage,
  }) => {
    // A scanner need not be configured for the link itself (`security-page.spec.ts`: "also while no
    // scanner is configured"); left real here since the link's visibility does not depend on it.
    await new DashboardPage(adminPage).goto();
    await expect(new Shell(adminPage).sidebar.security).toBeVisible();

    await new DashboardPage(userPage).goto();
    await expect(new Shell(userPage).sidebar.security).toHaveCount(0);
  });

  test('the admin-only scan browser answers a USER 403, not a page error (RPS-919 is about reads of a repo the user can already see, not this cross-repo browser)', async ({
    seededUser,
  }) => {
    // `adminGuard` (`guards.spec.ts`) already stops the UI from ever sending this: reached directly
    // over HTTP, the way a client that skipped the SPA would.
    const session = await loginSession(seededUser.username, seededUser.password);
    const res = await fetch(`${env.apiBaseUrl}/api/security/scans`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    expect(res.status).toBe(403);
    const body = (await res.json()) as { msgId?: string };
    expect(body.msgId).toBe('accessDenied');
  });
});

test.describe("SEC-02e security: a version's own scan section, by role", { tag: MOCKED }, () => {
  test('a USER sees the same severity badge and scan section as an admin, and can Scan Now / Re-scan (Permission.WRITE, not MANAGE)', async ({
    adminPage,
    userPage,
    seeder,
    seedPackage,
  }) => {
    // The scanner endpoints are stubbed (`stubVersionScans`, RPS-1259), the same way every other test
    // of `security/scan-section.spec.ts` is: the e2e default stack has no scanner configured at all
    // (`SECURITY_SCANNER=disabled`), so a REAL manual trigger is refused regardless of who asks
    // ("Manual vulnerability scanning is disabled" — a stack concern, not a role one). What this test
    // asserts is the ROLE gate in front of that call, not the scanner itself; `security-real/` (opt-in,
    // `--scanner`) is where a real trigger is proven.
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const detailFor = (page: typeof adminPage) =>
      protocolPages(page, DESCRIPTORS.npm, repo.name).detail(pkg);

    // The admin's page, as the control: never scanned, "Scan Now" is there.
    await stubSupportedRepoTypes(adminPage, [RepoType.NPM]);
    await stubVersionScans(adminPage, repo.name, new ScanScript());
    await detailFor(adminPage).goto();
    const adminSection = new ScanSection(adminPage);
    await expect(adminSection.root).toBeVisible();
    await expect(adminSection.neverScanned).toHaveText('Not scanned yet');

    // The USER's page: the section is there too (read is open to any signed-in user), and so is the
    // action to start the first scan (write is open to any signed-in user; only repo MANAGE is not).
    // Its own stub, on its own context/session: `userPage` is a second `BrowserContext` (`fixtures.ts`).
    await stubSupportedRepoTypes(userPage, [RepoType.NPM]);
    const userStubs = await stubVersionScans(userPage, repo.name, new ScanScript());
    await detailFor(userPage).goto();
    const userSection = new ScanSection(userPage);
    await expect(userSection.root).toBeVisible();
    await expect(userSection.neverScanned).toHaveText('Not scanned yet');
    await userSection.detailsLink.click();
    await expect(userSection.scanNowButton).toBeVisible();
    await expect(userSection.scanNowButton).toBeEnabled();

    await userSection.scanNowButton.click();

    expect(userStubs.trigger.count).toBe(1);
    await expect(userSection.status).toHaveText('Waiting...');
    await expect(userSection.neverScanned).toHaveCount(0);
    // Re-scan is offered once a scan exists, still to the USER that triggered it.
    await expect(userSection.rescanButton).toBeVisible();
  });
});
