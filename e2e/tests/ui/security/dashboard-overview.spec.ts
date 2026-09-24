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
 * SEC-02e: the dashboard's Security Overview card: "Repositories with Critical/High Findings" and
 * "Total Repositories", both read from `GET /api/repos/security-summary` (one entry per repository).
 * With the e2e stack's scanner off the real answer has no severity anywhere, so the card reads 0; the
 * stubbed answers prove it counts what it is given. DASH-01 already compares the total with the API.
 */
import { DashboardPage } from '../../../src/ui/pages/dashboard.js';
import { expect, test } from '../../../src/ui/security-fixtures.js';
import { Severity, stubRepoSecuritySummary } from '../../../src/ui/security-stubs.js';

const MOCKED = '@mocked';

test.describe('SEC-02e Security Overview', { tag: MOCKED }, () => {
  test('reads 0 critical/high findings with the scanner off', async ({
    adminPage,
    adminSession,
  }) => {
    const dashboard = new DashboardPage(adminPage);
    const response = await adminPage.request.get('/api/repos/security-summary', {
      headers: { Authorization: `Bearer ${adminSession.token}` },
    });
    const real = ((await response.json()) as { data: Record<string, { severity?: string | null }> })
      .data;
    // The premise: nothing in the stack has ever been scanned.
    expect(Object.values(real).filter((entry) => entry.severity)).toEqual([]);

    await dashboard.open({ admin: true });

    await expect(dashboard.securityCard).toContainText('Security Overview');
    await expect(dashboard.securityCriticalHigh).toHaveText('0');
    await expect(dashboard.securityTotal).toHaveText(/^\s*\d+\s*$/);
  });

  test('counts the repositories with critical or high findings out of all of them', async ({
    adminPage,
  }) => {
    const summary = await stubRepoSecuritySummary(adminPage, {
      'stub-critical': { severity: Severity.CRITICAL, scanned: true },
      'stub-high-a': { severity: Severity.HIGH, scanned: true },
      'stub-high-b': { severity: Severity.HIGH, scanned: true },
      'stub-medium': { severity: Severity.MEDIUM, scanned: true },
      'stub-low': { severity: Severity.LOW, scanned: true },
      'stub-unknown': { severity: Severity.UNKNOWN, scanned: true },
      'stub-clean': { severity: null, scanned: true },
      'stub-scanning': { scanned: false, unscannedInProgressCount: 1 },
    });
    const dashboard = new DashboardPage(adminPage);

    await dashboard.open({ admin: true });

    await expect(dashboard.securityCriticalHigh).toHaveText('3');
    await expect(dashboard.securityTotal).toHaveText('8');
    expect(summary.count).toBeGreaterThanOrEqual(1);
  });

  test('shows 0 critical/high when every repository is clean or lower', async ({ adminPage }) => {
    await stubRepoSecuritySummary(adminPage, {
      'stub-medium': { severity: Severity.MEDIUM, scanned: true },
      'stub-clean': { severity: null, scanned: true },
    });
    const dashboard = new DashboardPage(adminPage);

    await dashboard.open({ admin: true });

    await expect(dashboard.securityCriticalHigh).toHaveText('0');
    await expect(dashboard.securityTotal).toHaveText('2');
  });
});
