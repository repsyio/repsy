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
 * Ruby package pages (RPS-1257): the shared scenarios PKG-ruby-01..06 (`registerPackageScenarios`) and
 * PKG-ruby-07, the Ruby-only parts: the yanked badge after a yank through the API (on the versions list
 * and on the gem detail), the Latest link, and the gem detail's install commands and metadata. Gems
 * are published with the raw `gem push` request and a `.gem` built in code (never `gem`).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import {
  buildGem,
  infoRelPath,
  rawGet,
  rawPublish,
  rawYank,
} from '../../../src/clients/ruby-raw.js';
import { repoUrl } from '../../../src/repo-url.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { registerPackageScenarios } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const ruby = DESCRIPTORS.ruby;

registerPackageScenarios(ruby);

test.describe('Ruby gem pages', { tag: '@packages' }, () => {
  test('PKG-ruby-07 a yanked version shows the yanked badge on the versions list and on its detail', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY);
    const [one, two] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const pages = protocolPages(adminPage, ruby, repo.name);
    const versions = pages.versions(one);

    // Before the yank there is no badge anywhere.
    await versions.goto();
    await expect(versions.inRow(two, 'row-yanked')).toHaveCount(0);
    await expect(pages.detail(two).byId('pkg-detail-yanked')).toHaveCount(0);

    const yank = await rawYank(repo.name, adminCredential(), {
      gemName: two.name,
      version: two.version,
    });
    expect(yank.status, 'gem yank').toBe(200);

    await versions.goto();
    await expect(versions.rows()).toHaveCount(2);
    await expect(versions.inRow(two, 'row-yanked')).toBeVisible();
    await expect(versions.inRow(two, 'row-yanked')).toHaveText(/yanked/i);
    // Only the yanked version has it.
    await expect(versions.inRow(one, 'row-yanked')).toHaveCount(0);

    const detail = pages.detail(two);
    await detail.goto();
    await expect(detail.byId('pkg-detail-yanked')).toHaveText('(yanked)');
    await expect(detail.byId('pkg-detail-version')).toContainText('2.0.0');
    // The install snippet is still there for a yanked version.
    await expect(detail.installText).toContainText(`gem install ${two.name} -v 2.0.0`);

    const other = pages.detail(one);
    await other.goto();
    await expect(other.byId('pkg-detail-yanked')).toHaveCount(0);
  });

  test('RPS-1426 deleting a yanked version from the versions page keeps the live version', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY);
    const [one, two] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const yank = await rawYank(repo.name, adminCredential(), {
      gemName: two.name,
      version: two.version,
    });
    expect(yank.status, 'gem yank').toBe(200);
    const versions = protocolPages(adminPage, ruby, repo.name).versions(one);
    await versions.goto();
    await versions.expectRow(two);

    // The yanked version is the one deleted; the only live version must survive it.
    await versions.deleteRow(two);
    await versions.expectNoRow(two);
    await versions.expectRow(one);

    await versions.goto();
    await versions.expectNoRow(two);
    await versions.expectRow(one);
    const info = await rawGet(repo.name, adminCredential(), infoRelPath(one.name));
    expect(info.status, 'the gem is still served').toBe(200);
    expect(info.body.toString('utf8')).toContain('1.0.0');
  });

  test('PKG-ruby-07 the gem detail shows the install commands, the platform and the checksum', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY);
    const name = `e2e_${seeder.runId}_meta`;
    const gem = await buildGem({ name, version: '1.2.3' });
    const res = await rawPublish(repo.name, adminCredential(), gem.bytes);
    expect(res.status, 'gem push').toBeLessThan(300);
    const detail = protocolPages(adminPage, ruby, repo.name).detail({ name, version: '1.2.3' });
    await detail.goto();
    const source = repoUrl(repo.name, '');

    await expect(detail.name).toHaveText(name);
    await expect(detail.byId('pkg-detail-version')).toContainText('1.2.3');
    await expect(detail.byId('pkg-detail-published')).toContainText('Published:');
    await expect(detail.installText).toHaveText(`gem install ${name} -v 1.2.3 --source ${source}`);
    await expect(detail.snippet('gemfile')).toContainText(`source "${source}" do`);
    await expect(detail.snippet('gemfile')).toContainText(`gem "${name}", "1.2.3"`);
    await expect(detail.byId('pkg-detail-metadata')).toContainText('Platform: ruby');
    await expect(detail.byId('pkg-detail-metadata')).toContainText(gem.sha256Hex);
    // A gem with no dependencies has neither dependency block.
    await expect(detail.byId('pkg-detail-runtime-deps')).toHaveCount(0);
    await expect(detail.byId('pkg-detail-dev-deps')).toHaveCount(0);
  });

  test('PKG-ruby-07 the Latest link of a gem row opens the latest version', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.RUBY);
    const [one, two] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const pages = protocolPages(adminPage, ruby, repo.name);
    const list = pages.list();
    await list.goto();
    await expect(list.inRow(one, 'row-latest-link')).toContainText('2.0.0');
    await list.inRow(one, 'row-latest-link').click();
    await expect(adminPage).toHaveURL(new RegExp(`/${repo.name}/${two.name}/2\\.0\\.0$`));
    await pages.detail(two).expectLoaded();
  });
});
