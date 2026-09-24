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
 * NAV-01: the breadcrumb of a package page navigates to every level above it. A package is seeded
 * over raw HTTP (`seedPackage`), the test opens its deepest page and then walks UP the breadcrumb one
 * link at a time, asserting the URL, the crumbs that remain and the page that renders after each
 * click. Maven has the longest trail (Repositories, repo, group, artifact, version); npm's scoped
 * package shows the `@` the URL does not have.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';
import { RepositoriesPage } from '../../../src/ui/pages/repositories.js';
import { Breadcrumb } from './breadcrumb.js';

test.describe('Breadcrumb navigation', () => {
  test('NAV-01: the Maven trail leads back through artifact, group and repository to the list', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.MAVEN);
    const pkg = await seedPackage(repo);
    const [group, artifact] = pkg.name.split(':');
    const pages = protocolPages(adminPage, DESCRIPTORS.maven, repo.name);
    const crumbs = new Breadcrumb(adminPage);

    await pages.detail(pkg).goto();
    await crumbs.expectCrumbs(['Repositories', repo.name, group, artifact, pkg.version]);

    // version -> artifact: the versions page, the version's own row is there.
    await crumbs.follow(3, `/${repo.name}/${group}/${artifact}`);
    await pages.versions(pkg).expectLoaded();
    await pages.versions(pkg).expectRow(pkg);
    await crumbs.expectCrumbs(['Repositories', repo.name, group, artifact]);

    // artifact -> group: the group's artifact list.
    await crumbs.follow(2, `/${repo.name}/${group}`);
    await pages.sublist(pkg).expectLoaded();
    await pages.sublist(pkg).expectRow(pkg);
    await crumbs.expectCrumbs(['Repositories', repo.name, group]);

    // group -> repository: the repository's list (rows keyed group:artifact).
    await crumbs.follow(1, `/${repo.name}`);
    await pages.list().expectLoaded();
    await pages.list().expectRow(pkg);
    await crumbs.expectCrumbs(['Repositories', repo.name]);

    // repository -> Repositories: the repository list of the whole panel.
    const repos = new RepositoriesPage(adminPage);
    await crumbs.follow(0, '/repositories');
    await expect(repos.title).toBeVisible();
    await expect(repos.spinner.root).toBeHidden();
    await expect(repos.rows().first()).toBeVisible();
    await expect(crumbs.root).toHaveCount(0);
  });

  test('NAV-01: the npm trail of a scoped package shows the @scope and leads back up', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.NPM);
    const pkg = await seedPackage(repo);
    const [scope, name] = pkg.name.split('/');
    expect(scope, 'the default npm package is scoped').toMatch(/^@/);
    const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
    const crumbs = new Breadcrumb(adminPage);

    await pages.detail(pkg).goto();
    await crumbs.expectCrumbs(['Repositories', repo.name, scope, name, pkg.version]);

    // version -> package: the versions page.
    await crumbs.follow(3, `/${repo.name}/${scope.slice(1)}/${name}`);
    await pages.versions(pkg).expectLoaded();
    await pages.versions(pkg).expectRow(pkg);

    // package -> scope: the scope's package list (the URL has no @).
    await crumbs.follow(2, `/${repo.name}/${scope.slice(1)}`);
    await pages.sublist(pkg).expectLoaded();
    await pages.sublist(pkg).expectRow(pkg);
    await crumbs.expectCrumbs(['Repositories', repo.name, scope]);

    // scope -> repository.
    await crumbs.follow(1, `/${repo.name}`);
    await pages.list().expectLoaded();
    await crumbs.expectCrumbs(['Repositories', repo.name]);
  });
});
