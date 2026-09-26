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
 * CLP-04 (RPS-1623, G19): the reset-password modal's copy buttons on a plain-HTTP install, where
 * `navigator.clipboard` does not exist (`src/ui/plain-http.ts`). The password is shown once, so a copy
 * button that silently does nothing loses it: the fallback copies it and the toast says so, and when no
 * copy method works the toast says to copy by hand instead of claiming success.
 */
import { expect, test } from '../../../src/ui/users-fixtures.js';
import { recordedCopies, simulatePlainHttp } from '../../../src/ui/plain-http.js';

// @cloud-skip: the admin Users page exists on Repsy OS only (`target.ui.hasUsersPage`).
test.describe('CLP-04 reset password copy', { tag: ['@cloud-skip'] }, () => {
  test('the copy buttons copy the one-time password through the fallback', async ({
    adminPage,
    context,
    usersPage,
    seededUser,
  }) => {
    await simulatePlainHttp(context);
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.clickResetPassword(seededUser.username);
    await usersPage.shell.dangerModal.confirm();
    const modal = usersPage.resetPasswordModal;
    await modal.expectOpen();
    const password = await modal.secret();
    expect(await adminPage.evaluate(() => navigator.clipboard)).toBeUndefined();

    await modal.copyValue.click();
    await usersPage.shell.toasts.expectSuccess('Copied to clipboard');
    await modal.copyFooter.click();

    await expect.poll(async () => (await recordedCopies(adminPage)).length).toBe(2);
    const copies = await recordedCopies(adminPage);
    expect(copies.map((copy) => copy.ok)).toEqual([true, true]);
    expect(copies.map((copy) => copy.text)).toEqual([password, password]);
  });

  test('when no copy method works the modal says to copy by hand, not "Copied"', async ({
    adminPage,
    context,
    usersPage,
    seededUser,
    pageErrors,
  }) => {
    pageErrors.allow(
      /Could not copy/,
      'by design: the copy failed on purpose, the modal asks the admin to copy by hand',
    );
    await simulatePlainHttp(context, { execResult: 'fail' });
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.clickResetPassword(seededUser.username);
    await usersPage.shell.dangerModal.confirm();
    const modal = usersPage.resetPasswordModal;
    await modal.expectOpen();

    await modal.copyValue.click();

    await usersPage.shell.toasts.expectError(/copy it manually/);
    await expect(usersPage.shell.toasts.success('Copied to clipboard')).toHaveCount(0);
    expect((await recordedCopies(adminPage)).map((copy) => copy.ok)).toEqual([false]);
  });
});
