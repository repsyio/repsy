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

/**
 * Cargo package pages (RPS-1257): the shared scenarios PKG-cargo-01..06 (`registerPackageScenarios`)
 * and PKG-cargo-07, the Cargo-only parts: the crate detail (README, dependencies in the Cargo.toml
 * block, the Add Dependency and Install Binary branches), the sorts, and deleting a crate with several
 * versions. Crates are published with `publishCrate`, the raw `cargo publish` body with a `.crate`
 * built in code (never the `cargo` binary).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { rawYank } from '../../../src/clients/cargo-raw.js';
import { publishCrate } from '../../../src/seed/packages/cargo.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { registerPackageScenarios, rowKeys } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const cargo = DESCRIPTORS.cargo;

// RPS-1262 (2): the Cargo version list only has the `hidden ... lg:block` desktop table, no mobile
// cards, so at a phone's width the page renders nothing but the pager. The template's mobile-versions
// step (a card per version, no Delete for a USER) can only fail today.
// Not filed yet (see the PR): "Newest" and "Oldest" order by `max_version`, a text column, not by
// publish time, so the shared "sort" step (which publishes three crates at the same version, one after
// the other) cannot see its order; PKG-cargo-07 below asserts the sorts with crates of differing versions.
registerPackageScenarios(cargo, {
  knownFailures: {
    '02-sort':
      'unfiled: Newest/Oldest sort by max_version (text), so crates at one version have no publish order',
    '05-mobile-versions': 'RPS-1262: the version list renders no cards below lg',
  },
});

const README = '# Cargo README\n\nA **bold** claim with `code`.\n';

test.describe('Cargo crate pages', { tag: '@packages' }, () => {
  test('PKG-cargo-07 the crate detail renders the README as markdown', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const name = `e2e_${seeder.runId}_readme`;
    const pkg = await publishCrate(repo.name, name, '1.0.0', { readme: README });
    const detail = protocolPages(adminPage, cargo, repo.name).detail(pkg);
    await detail.goto();

    await expect(detail.readme).toBeVisible();
    await expect(
      detail.readme.getByRole('heading', { name: 'Cargo README', level: 1 }),
    ).toBeVisible();
    await expect(detail.readme.locator('strong')).toHaveText('bold');
    await expect(detail.readme.locator('code')).toHaveText('code');
    await expect(detail.readme).not.toContainText('# Cargo README');
    await expect(detail.root.getByRole('heading', { name: 'README', exact: true })).toBeVisible();
  });

  test('PKG-cargo-07 a crate published without a README has no README section', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, cargo, repo.name).detail(pkg);
    await detail.goto();
    await expect(detail.root).toBeVisible();
    await expect(detail.readme).toHaveCount(0);
  });

  test('PKG-cargo-07 the Cargo.toml block and the metadata show what was published', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const name = `e2e_${seeder.runId}_deps`;
    const pkg = await publishCrate(repo.name, name, '1.2.3', {
      description: 'a crate with dependencies',
      deps: [
        {
          name: 'serde',
          version_req: '^1.0',
          features: ['derive'],
          optional: false,
          default_features: true,
          target: null,
          kind: 'normal',
          registry: null,
          explicit_name_in_toml: null,
        },
        {
          name: 'tempfile',
          version_req: '^3',
          features: [],
          optional: false,
          default_features: true,
          target: null,
          kind: 'dev',
          registry: null,
          explicit_name_in_toml: null,
        },
      ],
    });
    const detail = protocolPages(adminPage, cargo, repo.name).detail(pkg);
    await detail.goto();

    const toml = detail.snippet('cargo-toml');
    await expect(toml).toContainText('[package]');
    await expect(toml).toContainText(`name = "${name}"`);
    await expect(toml).toContainText('version = "1.2.3"');
    await expect(toml).toContainText('license = "MIT"');
    await expect(toml).toContainText('edition = "2021"');
    await expect(toml).toContainText('[dependencies]');
    await expect(toml).toContainText('serde = { version = "^1.0", features = ["derive"] }');
    await expect(toml).toContainText('[dev-dependencies]');
    await expect(toml).toContainText('tempfile = "^3"');

    await expect(detail.name).toHaveText(name);
    await expect(detail.byId('pkg-detail-version')).toHaveText('1.2.3');
    await expect(detail.byId('pkg-detail-published')).toContainText('Uploaded at:');
    await expect(detail.byId('pkg-detail-metadata')).toContainText(`Crate: ${name}`);
    await expect(detail.byId('pkg-detail-metadata')).toContainText('Downloads: 0');
    await expect(detail.byId('pkg-detail-metadata')).toContainText('Rust Version: Not specified');
  });

  test('PKG-cargo-07 a library crate offers Add Dependency, a binary crate Install Binary', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const lib = await publishCrate(repo.name, `e2e_${seeder.runId}_lib`, '1.0.0');
    const bin = await publishCrate(repo.name, `e2e_${seeder.runId}_bin`, '1.0.0', { bin: true });
    const pages = protocolPages(adminPage, cargo, repo.name);

    const libDetail = pages.detail(lib);
    await libDetail.goto();
    await expect(libDetail.root.getByRole('heading', { name: 'Add Dependency:' })).toBeVisible();
    await expect(libDetail.installText).toHaveText(`cargo add ${lib.name}@1.0.0 --registry repsy`);

    const binDetail = pages.detail(bin);
    await binDetail.goto();
    await expect(binDetail.root.getByRole('heading', { name: 'Install Binary:' })).toBeVisible();
    await expect(binDetail.installText).toHaveText(
      `cargo install ${bin.name} --version 1.0.0 --registry repsy`,
    );
  });

  test('PKG-cargo-07 the sorts order crates by highest version and by name', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    // Named against their versions, so name order (a, b, c) is not version order (2, 3, 1).
    const a = await publishCrate(repo.name, `e2e_${seeder.runId}_a`, '2.0.0');
    const b = await publishCrate(repo.name, `e2e_${seeder.runId}_b`, '3.0.0');
    const c = await publishCrate(repo.name, `e2e_${seeder.runId}_c`, '1.0.0');
    const list = protocolPages(adminPage, cargo, repo.name).list();
    await list.goto();

    const order: Record<string, string[]> = {
      Newest: [b.name, a.name, c.name],
      Oldest: [c.name, a.name, b.name],
      'Name (A-Z)': [a.name, b.name, c.name],
      'Name (Z-A)': [c.name, b.name, a.name],
    };
    await expect.poll(() => rowKeys(list)).toEqual(order['Newest']);
    for (const option of ['Oldest', 'Name (A-Z)', 'Name (Z-A)', 'Newest']) {
      await list.sortBy(option);
      await expect.poll(() => rowKeys(list), option).toEqual(order[option]);
    }
  });

  test('PKG-cargo-07 the crate row shows the highest version, and the versions list every one', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const name = `e2e_${seeder.runId}_multi`;
    const versions = ['1.0.0', '1.1.0', '2.0.0'];
    const seeded = [];
    for (const version of versions) {
      seeded.push(await publishCrate(repo.name, name, version));
    }
    const pages = protocolPages(adminPage, cargo, repo.name);
    const list = pages.list();
    await list.goto();
    await expect(list.inRow(seeded[0], 'row-latest')).toHaveText('2.0.0');
    await expect(list.inRow(seeded[0], 'row-downloads')).toHaveText('0');
    await expect(list.rows()).toHaveCount(1);

    const page = pages.versions(seeded[0]);
    await page.goto();
    await expect(page.rows()).toHaveCount(3);
    for (const version of seeded) {
      await page.expectRow(version);
    }
    // Newest is the default: the highest version last published is on top.
    await expect.poll(() => rowKeys(page)).toEqual([...versions].reverse());
  });

  test('PKG-cargo-07 deleting a crate from the list removes every one of its versions', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const name = `e2e_${seeder.runId}_gone`;
    const one = await publishCrate(repo.name, name, '1.0.0');
    await publishCrate(repo.name, name, '2.0.0');
    const keep = await publishCrate(repo.name, `e2e_${seeder.runId}_keep`, '1.0.0');
    const pages = protocolPages(adminPage, cargo, repo.name);
    const list = pages.list();
    await list.goto();

    await list.deleteRow(one);
    await list.expectNoRow(one);
    await list.expectRow(keep);

    // The versions page of the deleted crate has nothing left to show.
    const versions = pages.versions(one);
    await versions.goto();
    await expect(versions.rows()).toHaveCount(0);
  });

  test('PKG-cargo-07 a yanked version stays listed and its detail opens (the panel shows no yank state)', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.CARGO);
    const name = `e2e_${seeder.runId}_yank`;
    const first = await publishCrate(repo.name, name, '1.0.0');
    const second = await publishCrate(repo.name, name, '2.0.0');
    const res = await rawYank(repo.name, adminCredential(), name, '2.0.0');
    expect(res.status, 'yank').toBe(200);

    const pages = protocolPages(adminPage, cargo, repo.name);
    const versions = pages.versions(first);
    await versions.goto();
    await versions.expectRow(first);
    await versions.expectRow(second);
    await expect(versions.inRow(second, 'row-name')).toHaveText('2.0.0');
    await expect(versions.toolbar.getByText(/yanked/i)).toHaveCount(0);

    const detail = pages.detail(second);
    await detail.goto();
    await expect(detail.byId('pkg-detail-version')).toHaveText('2.0.0');
    await expect(detail.root).not.toContainText(/yanked/i);
  });

  // RPS-1299: the open menu of a non-last row paints under the NEXT row, so a real mouse click on its
  // Delete item is refused by Playwright's hit check ("<div ...> intercepts pointer events") and, by
  // hand, lands on that row; `openDeleteDialog` dispatches the click to get past it.
  test.fail(
    'PKG-cargo-07 the row menu of a non-last row takes a real mouse click on Delete (RPS-1299)',
    async ({ adminPage, seeder, seedPackage }) => {
      const repo = await seeder.createRepo(RepoType.CARGO);
      for (const index of [1, 2, 3]) {
        await seedPackage(repo, { index });
      }
      const list = protocolPages(adminPage, cargo, repo.name).list();
      await list.goto();
      // The first row in the DOM is the one whose menu the row after it can cover.
      const [top] = await rowKeys(list);
      const menu = await list.openRowMenu({ name: top, version: '1.0.0' });
      await menu.getByTestId('row-delete').click({ timeout: 3_000 });
      await list.dangerModal.expectOpen('Delete Crate');
      await expect(list.dangerModal.root).toBeVisible();
    },
  );
});
