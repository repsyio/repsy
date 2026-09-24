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
 * USR-03 (edit) and USR-05 (delete) on users seeded through the API.
 *
 * LAST-ADMIN SEMANTICS. The panel decides "this is the last admin" from the server's admin count
 * (`GET /api/users/admin-count`, RPS-1246), not from the page it shows; the server also guards the REAL
 * last admin (`cannotDeleteLastAdminUser` / `cannotDemoteLastAdminUser`, covered by the backend ITs).
 * The harness admin always exists and is never edited, demoted or deleted, so these specs can never
 * reach the real last-admin state; the panel's last-admin branch is covered by the component spec. What
 * they check is the other side: a view that holds a single seeded admin (search for its exact username)
 * does not treat it as the last one while another admin exists.
 *
 * A search is also what keeps every view deterministic on a stack other tests are writing to.
 */
import { UserRole } from '../../../src/api/panel-api.js';
import { USERNAME_TEXT, bulleted } from '../../../src/ui/credential-messages.js';
import { expect, test } from '../../../src/ui/users-fixtures.js';

test.describe('USR-03 edit a user', () => {
  test('editing the username renames the user', async ({
    usersPage,
    seededUser,
    seeder,
    panelApi,
  }) => {
    const renamed = seeder.reserveUsername();

    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.openEdit(seededUser.username);
    await expect(usersPage.editModal.roleLabel).toHaveText('User');
    await usersPage.editModal.username.fill(renamed);
    await usersPage.editModal.submit.click();

    await usersPage.shell.toasts.expectSuccess('User updated successfully');
    await expect(usersPage.editModal.root).toBeHidden();

    // The user keeps its id (the seeder still cleans it up), only the name changed.
    const matches = await panelApi.listUsers({ q: renamed });
    expect(matches.find((user) => user.username === renamed)?.id).toBe(seededUser.id);
    expect(await panelApi.listUsers({ q: seededUser.username })).toHaveLength(0);

    await usersPage.search(renamed);
    await expect(usersPage.row(renamed)).toBeVisible();
    await expect(usersPage.role(renamed)).toHaveText('USER');
  });

  test('after an edit the list reloads without the old search, so the renamed user does not vanish', async ({
    usersPage,
    seededUser,
    seeder,
  }) => {
    const renamed = seeder.reserveUsername();

    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.openEdit(seededUser.username);
    await usersPage.editModal.username.fill(renamed);
    const reload = usersPage.listResponse('', 0);
    await usersPage.editModal.submit.click();

    await usersPage.shell.toasts.expectSuccess('User updated successfully');
    // The old name no longer matches anything: with the search still applied the list would be
    // empty ("No users found, create your first user"). It reloads without it, and the box agrees.
    await reload;
    await expect(usersPage.searchInput).toHaveValue('');
    await expect(usersPage.empty).toHaveCount(0);
    await expect(usersPage.rows().first()).toBeVisible();
  });

  test('promoting a user to admin changes the role badge', async ({
    usersPage,
    seededUser,
    panelApi,
  }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await expect(usersPage.role(seededUser.username)).toHaveText('USER');

    await usersPage.openEdit(seededUser.username);
    await usersPage.editModal.roleToggle.click();
    await expect(usersPage.editModal.roleSwitch).toBeChecked();
    await expect(usersPage.editModal.roleLabel).toHaveText('Admin');
    await usersPage.editModal.submit.click();

    await usersPage.shell.toasts.expectSuccess('User updated successfully');
    // The list reloads without the search (see the rename test): look the user up again.
    await usersPage.search(seededUser.username);
    await expect(usersPage.role(seededUser.username)).toHaveText('ADMIN');
    const stored = (await panelApi.listUsers({ q: seededUser.username })).find(
      (user) => user.id === seededUser.id,
    );
    expect(stored?.role).toBe(UserRole.ADMIN);
  });

  test('an admin can be demoted while another admin is in the list', async ({
    usersPage,
    seeder,
    panelApi,
  }) => {
    const first = await seeder.createUser({ role: UserRole.ADMIN });
    const second = await seeder.createUser({ role: UserRole.ADMIN });

    await usersPage.goto();
    // The run id is in both names: this view holds two admins, so neither is "the last" to the panel.
    await usersPage.search(seeder.runId);
    await expect(usersPage.row(first.username)).toBeVisible();
    await expect(usersPage.row(second.username)).toBeVisible();

    await usersPage.openEdit(first.username);
    await expect(usersPage.editModal.lastAdminWarning).toHaveCount(0);
    await expect(usersPage.editModal.roleSwitch).toBeEnabled();
    await usersPage.editModal.roleToggle.click();
    await expect(usersPage.editModal.roleSwitch).not.toBeChecked();
    await usersPage.editModal.submit.click();

    await usersPage.shell.toasts.expectSuccess('User updated successfully');
    await usersPage.search(seeder.runId);
    await expect(usersPage.role(first.username)).toHaveText('USER');
    await expect(usersPage.role(second.username)).toHaveText('ADMIN');
    const stored = (await panelApi.listUsers({ q: first.username })).find(
      (user) => user.id === first.id,
    );
    expect(stored?.role).toBe(UserRole.USER);
  });

  // RPS-1246: a view that holds a single admin is not "the last admin" while another admin exists.
  test('the last-admin warning is not shown while another admin exists', async ({
    usersPage,
    seeder,
  }) => {
    const admin = await seeder.createUser({ role: UserRole.ADMIN });

    await usersPage.goto();
    await usersPage.search(admin.username);
    await usersPage.openEdit(admin.username);

    await expect(usersPage.editModal.lastAdminWarning).toHaveCount(0);
    await expect(usersPage.editModal.roleSwitch).toBeEnabled();
  });

  test('the edit form validates the username', async ({ usersPage, seededUser }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.openEdit(seededUser.username);

    await usersPage.editModal.username.fill('Bad Name');
    await usersPage.editModal.username.blur();
    await expect(usersPage.editModal.error('pattern')).toHaveText(bulleted(USERNAME_TEXT.pattern));
    await expect(usersPage.editModal.submit).toBeDisabled();

    await usersPage.editModal.username.fill('ab');
    await expect(usersPage.editModal.error('minlength')).toHaveText(
      bulleted(USERNAME_TEXT.minlength),
    );
    await expect(usersPage.editModal.submit).toBeDisabled();

    await usersPage.editModal.username.fill('');
    await expect(usersPage.editModal.error('required')).toHaveText(
      bulleted(USERNAME_TEXT.required),
    );
    await expect(usersPage.editModal.submit).toBeDisabled();

    await usersPage.editModal.username.fill('a'.repeat(26));
    await expect(usersPage.editModal.error('maxlength')).toHaveText(
      bulleted(USERNAME_TEXT.maxlength),
    );
    await expect(usersPage.editModal.submit).toBeDisabled();
  });

  test('renaming to a taken username is refused and nothing changes', async ({
    usersPage,
    seeder,
    panelApi,
  }) => {
    const user = await seeder.createUser();
    const other = await seeder.createUser();

    await usersPage.goto();
    await usersPage.search(user.username);
    await usersPage.openEdit(user.username);
    await usersPage.editModal.username.fill(other.username);
    await usersPage.editModal.submit.click();

    await usersPage.shell.toasts.expectError(/in use/i);
    await expect(usersPage.editModal.root).toBeVisible();
    const stored = (await panelApi.listUsers({ q: user.username })).find(
      (candidate) => candidate.id === user.id,
    );
    expect(stored?.username).toBe(user.username);
  });

  test('cancelling the edit changes nothing', async ({
    usersPage,
    seededUser,
    seeder,
    panelApi,
  }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.openEdit(seededUser.username);
    await usersPage.editModal.username.fill(seeder.reserveUsername());
    await usersPage.editModal.cancel.click();

    await expect(usersPage.editModal.root).toBeHidden();
    await expect(usersPage.row(seededUser.username)).toBeVisible();
    expect(await panelApi.listUsers({ q: seededUser.username })).toHaveLength(1);
  });
});

test.describe('USR-05 delete a user', () => {
  test('cancelling keeps the user, confirming deletes it', async ({
    usersPage,
    seededUser,
    panelApi,
  }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);

    await usersPage.clickDelete(seededUser.username);
    await usersPage.shell.dangerModal.expectOpen('Delete User');
    await usersPage.shell.dangerModal.cancel();
    await usersPage.shell.dangerModal.expectClosed();
    await expect(usersPage.row(seededUser.username)).toBeVisible();
    expect(await panelApi.listUsers({ q: seededUser.username })).toHaveLength(1);

    const reload = usersPage.listResponse('', 0);
    await usersPage.deleteUser(seededUser.username);
    await usersPage.shell.toasts.expectSuccess('User deleted successfully');
    await expect(usersPage.row(seededUser.username)).toHaveCount(0);
    expect(await panelApi.listUsers({ q: seededUser.username })).toHaveLength(0);

    // The list reloads without the search that led to the user, and the box agrees: it does not
    // show the empty state for a search nobody typed any more.
    await reload;
    await expect(usersPage.searchInput).toHaveValue('');
    await expect(usersPage.empty).toHaveCount(0);
  });

  test('an admin can be deleted while another admin is in the list', async ({
    usersPage,
    seeder,
    panelApi,
  }) => {
    const first = await seeder.createUser({ role: UserRole.ADMIN });
    const second = await seeder.createUser({ role: UserRole.ADMIN });

    await usersPage.goto();
    await usersPage.search(seeder.runId);
    await expect(usersPage.row(second.username)).toBeVisible();
    const reload = usersPage.listResponse('', 0);
    await usersPage.deleteUser(first.username);

    await usersPage.shell.toasts.expectSuccess('User deleted successfully');
    await expect(usersPage.row(first.username)).toHaveCount(0);
    await reload;
    // A delete reloads the list WITHOUT the search, and that list is the whole instance, newest first:
    // ten users of parallel tests created in the meantime push `second` off page 1. Search again for
    // this run's users before asserting it is still there (RPS-1303).
    await usersPage.search(seeder.runId);
    await expect(usersPage.row(second.username)).toBeVisible();
    expect(await panelApi.listUsers({ q: first.username })).toHaveLength(0);
  });

  // RPS-1246: with the seeded admin alone in the view the delete is still offered, since the harness
  // admin exists. It only opens the confirmation and cancels it: nothing is deleted.
  test('deleting an admin is offered while another admin exists', async ({ usersPage, seeder }) => {
    const admin = await seeder.createUser({ role: UserRole.ADMIN });

    await usersPage.goto();
    await usersPage.search(admin.username);
    await usersPage.clickDelete(admin.username);

    await expect(usersPage.shell.dangerModal.root).toBeVisible();
    await usersPage.shell.dangerModal.cancel();
  });
});
