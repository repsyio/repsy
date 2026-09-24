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
 * LAST-ADMIN SEMANTICS. The panel decides "this is the last admin" on the client, from the admins in
 * the page it currently shows (`UserManagementComponent.isLastAdmin`, RPS-1246); the server guards the
 * REAL last admin (`cannotDeleteLastAdminUser` / `cannotDemoteLastAdminUser`, covered by the backend
 * ITs). These specs never create that state: the harness admin always exists, and it is never edited,
 * demoted or deleted. What they can reach, deterministically, is a view that holds a single admin, by
 * searching for a seeded admin's exact username. The client guard fires there although another admin
 * (the harness one) exists, which is the RPS-1246 bug: the guard tests below pin what the panel does
 * today in that view, and the two `test.fail` tests assert what it should do.
 *
 * A search is also what keeps every view deterministic on a stack other tests are writing to.
 */
import { UserRole } from '../../../src/api/panel-api.js';
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
    const matches = await panelApi.listUsers({ search: renamed });
    expect(matches.find((user) => user.username === renamed)?.id).toBe(seededUser.id);
    expect(await panelApi.listUsers({ search: seededUser.username })).toHaveLength(0);

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
    const stored = (await panelApi.listUsers({ search: seededUser.username })).find(
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
    const stored = (await panelApi.listUsers({ search: first.username })).find(
      (user) => user.id === first.id,
    );
    expect(stored?.role).toBe(UserRole.USER);
  });

  test('a single admin in the view cannot be demoted: warning, and the role switch is locked', async ({
    usersPage,
    seeder,
    panelApi,
  }) => {
    const admin = await seeder.createUser({ role: UserRole.ADMIN });

    await usersPage.goto();
    await usersPage.search(admin.username);
    await usersPage.openEdit(admin.username);

    await expect(usersPage.editModal.lastAdminWarning).toBeVisible();
    await expect(usersPage.editModal.lastAdminWarning).toContainText('This is the only admin user');
    await expect(usersPage.editModal.roleSwitch).toBeChecked();
    await expect(usersPage.editModal.roleSwitch).toBeDisabled();

    // The switch is locked: a click on it (dispatched, since Playwright would rather wait than click a
    // disabled control) changes nothing, and the form still says Admin.
    await usersPage.editModal.roleToggle.dispatchEvent('click');
    await expect(usersPage.editModal.roleLabel).toHaveText('Admin');
    await expect(usersPage.editModal.roleSwitch).toBeChecked();
    await usersPage.editModal.cancel.click();
    await expect(usersPage.editModal.root).toBeHidden();

    const stored = (await panelApi.listUsers({ search: admin.username })).find(
      (user) => user.id === admin.id,
    );
    expect(stored?.role).toBe(UserRole.ADMIN);
  });

  // RPS-1246: the client counts admins on the visible page only, so the seeded admin looks like the
  // last one although the harness admin exists. Asserts the intended behaviour; expected to fail today.
  test.fail(
    'the last-admin warning is not shown while another admin exists RPS-1246',
    async ({ usersPage, seeder }) => {
      const admin = await seeder.createUser({ role: UserRole.ADMIN });

      await usersPage.goto();
      await usersPage.search(admin.username);
      await usersPage.openEdit(admin.username);

      await expect(usersPage.editModal.lastAdminWarning).toHaveCount(0);
      await expect(usersPage.editModal.roleSwitch).toBeEnabled();
    },
  );

  test('the edit form validates the username', async ({ usersPage, seededUser }) => {
    await usersPage.goto();
    await usersPage.search(seededUser.username);
    await usersPage.openEdit(seededUser.username);

    await usersPage.editModal.username.fill('Bad Name');
    await usersPage.editModal.username.blur();
    await expect(usersPage.editModal.error('pattern')).toBeVisible();
    await expect(usersPage.editModal.submit).toBeDisabled();

    await usersPage.editModal.username.fill('ab');
    await expect(usersPage.editModal.error('minlength')).toBeVisible();
    await expect(usersPage.editModal.submit).toBeDisabled();

    await usersPage.editModal.username.fill('');
    await expect(usersPage.editModal.error('required')).toBeVisible();
    await expect(usersPage.editModal.submit).toBeDisabled();

    await usersPage.editModal.username.fill('a'.repeat(26));
    await expect(usersPage.editModal.error('maxlength')).toBeVisible();
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
    const stored = (await panelApi.listUsers({ search: user.username })).find(
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
    expect(await panelApi.listUsers({ search: seededUser.username })).toHaveLength(1);
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
    expect(await panelApi.listUsers({ search: seededUser.username })).toHaveLength(1);

    const reload = usersPage.listResponse('', 0);
    await usersPage.deleteUser(seededUser.username);
    await usersPage.shell.toasts.expectSuccess('User deleted successfully');
    await expect(usersPage.row(seededUser.username)).toHaveCount(0);
    expect(await panelApi.listUsers({ search: seededUser.username })).toHaveLength(0);

    // The list reloads without the search that led to the user, and the box agrees: it does not
    // show the empty state for a search nobody typed any more.
    await reload;
    await expect(usersPage.searchInput).toHaveValue('');
    await expect(usersPage.empty).toHaveCount(0);
  });

  test('a single admin in the view cannot be deleted: the client raises an error toast', async ({
    usersPage,
    seeder,
    panelApi,
  }) => {
    const admin = await seeder.createUser({ role: UserRole.ADMIN });

    await usersPage.goto();
    await usersPage.search(admin.username);
    await usersPage.clickDelete(admin.username);

    await usersPage.shell.toasts.expectError(
      'Cannot delete the last admin user. Create another admin first.',
    );
    // No confirmation is offered, and nothing was deleted.
    await usersPage.shell.dangerModal.expectClosed();
    await expect(usersPage.row(admin.username)).toBeVisible();
    expect(await panelApi.listUsers({ search: admin.username })).toHaveLength(1);
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
    await usersPage.deleteUser(first.username);

    await usersPage.shell.toasts.expectSuccess('User deleted successfully');
    await expect(usersPage.row(first.username)).toHaveCount(0);
    await expect(usersPage.row(second.username)).toBeVisible();
    expect(await panelApi.listUsers({ search: first.username })).toHaveLength(0);
  });

  // RPS-1246, same cause as above: with the seeded admin alone in the view the guard refuses the
  // delete although the harness admin exists. Asserts the confirmation is offered; expected to fail
  // today. It only opens the confirmation and cancels it: nothing is deleted either way.
  test.fail(
    'deleting an admin is offered while another admin exists RPS-1246',
    async ({ usersPage, seeder }) => {
      const admin = await seeder.createUser({ role: UserRole.ADMIN });

      await usersPage.goto();
      await usersPage.search(admin.username);
      await usersPage.clickDelete(admin.username);

      await expect(usersPage.shell.dangerModal.root).toBeVisible();
      await usersPage.shell.dangerModal.cancel();
    },
  );
});
