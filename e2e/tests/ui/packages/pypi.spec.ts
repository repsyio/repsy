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
 * PyPI package pages (RPS-1256): the shared scenarios PKG-pypi-01..06 (`registerPackageScenarios`) and
 * PKG-pypi-07, the PyPI-only parts of the release detail: the long description that renders, the
 * install snippet that carries the repo's simple index, the metadata and the release kind label.
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { buildUploadForm, buildWheel, uploadUrl } from '../../../src/clients/pypi-raw.js';
import { adminCredential, authHeader } from '../../../src/clients/raw-http.js';
import { repoUrl } from '../../../src/repo-url.js';
import type { SeededPackage } from '../../../src/seed/packages.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { registerPackageScenarios } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

const pypi = DESCRIPTORS.pypi;

registerPackageScenarios(pypi);

const DESCRIPTION = '# Long Title\n\nA **bold** description with `code`.\n';

/** Uploads a wheel the way `twine upload` does, with the long description and the home page a real project has. */
async function uploadRich(
  repoName: string,
  name: string,
  version: string,
  rich: { description?: string } = {},
): Promise<SeededPackage> {
  const form = buildUploadForm(buildWheel({ name, version }));
  if (rich.description !== undefined) {
    form.append('description', rich.description);
    form.append('description_content_type', 'text/markdown');
  }
  form.append('home_page', 'https://example.com/home');
  const res = await fetch(uploadUrl(repoName), {
    method: 'POST',
    headers: authHeader(adminCredential()),
    body: form,
  });
  expect(res.status, `pypi upload of ${name} ${version}`).toBe(200);
  return { protocol: 'pypi', repoName, name, version, extra: {} };
}

test.describe('PyPI release detail', { tag: '@packages' }, () => {
  test('PKG-pypi-07 the long description renders as markdown under Description', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const pkg = await uploadRich(repo.name, `e2e-${seeder.runId}-rich`, '1.0.0', {
      description: DESCRIPTION,
    });
    const detail = protocolPages(adminPage, pypi, repo.name).detail(pkg);
    await detail.goto();

    // Rendered (RPS-1142 is fixed), not the raw source.
    await expect(detail.readme).toBeVisible();
    await expect(
      detail.readme.getByRole('heading', { name: 'Long Title', level: 1 }),
    ).toBeVisible();
    await expect(detail.readme.locator('strong')).toHaveText('bold');
    await expect(detail.readme.locator('code')).toHaveText('code');
    await expect(detail.readme).not.toContainText('# Long Title');
    await expect(detail.readme).not.toContainText('**bold**');
    await expect(detail.root.getByRole('heading', { name: 'Description' })).toBeVisible();
  });

  test('PKG-pypi-07 a release without a description has no Description section', async ({
    adminPage,
    seeder,
    seedPackage,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const pkg = await seedPackage(repo);
    const detail = protocolPages(adminPage, pypi, repo.name).detail(pkg);
    await detail.goto();
    await expect(detail.root).toBeVisible();
    await expect(detail.readme).toHaveCount(0);
  });

  test('PKG-pypi-07 the install snippet uses the repository URL and the metadata shows the upload', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const pkg = await uploadRich(repo.name, `e2e-${seeder.runId}-meta`, '1.2.3');
    const detail = protocolPages(adminPage, pypi, repo.name).detail(pkg);
    await detail.goto();

    // The exact command, index URL included: the stack's repo URL and the repo's own /simple index.
    await expect(detail.installText).toHaveText(
      `pip install ${pkg.name}==${pkg.version} --extra-index-url ${repoUrl(repo.name, 'simple')}`,
    );
    await expect(detail.byId('pkg-detail-version')).toHaveText(pkg.version);
    await expect(detail.byId('pkg-detail-published')).toContainText('Uploaded at:');
    await expect(detail.byId('pkg-detail-requires-python')).toContainText('>=3.9');
    await expect(detail.byId('pkg-detail-homepage')).toHaveAttribute(
      'href',
      'https://example.com/home',
    );
  });

  test('PKG-pypi-07 the release kind reads Final, Pre and Dev release', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const name = `e2e-${seeder.runId}-kinds`;
    const kinds: [string, string][] = [
      ['1.0.0', 'Final release:'],
      ['2.0.0rc1', 'Pre release:'],
      ['3.0.0.dev1', 'Dev Release:'],
    ];
    for (const [version] of kinds) {
      await uploadRich(repo.name, name, version);
    }
    const pages = protocolPages(adminPage, pypi, repo.name);
    for (const [version, label] of kinds) {
      const detail = pages.detail({ name, version });
      await detail.goto();
      await expect(detail.byId('pkg-detail-release-kind')).toHaveText(label);
      await expect(detail.byId('pkg-detail-version')).toHaveText(version);
    }
  });

  // RPS-1261 (5): a post release (`1.0.0.post1`) used to be labelled "Pre release:".
  test('PKG-pypi-07 a post release is not labelled as a pre release (RPS-1261)', async ({
    adminPage,
    seeder,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const pkg = await uploadRich(repo.name, `e2e-${seeder.runId}-post`, '1.0.0.post1');
    const detail = protocolPages(adminPage, pypi, repo.name).detail(pkg);
    await detail.goto();
    await expect(detail.byId('pkg-detail-version')).toHaveText('1.0.0.post1');
    await expect(detail.byId('pkg-detail-release-kind')).toHaveText('Post release:');
  });
});

test.describe('PyPI package list', { tag: '@packages' }, () => {
  test('PKG-pypi-07 the desktop row shows the latest version in its Latest link', async ({
    adminPage,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const [, latest] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const list = protocolPages(adminPage, pypi, repo.name).list();
    await list.goto();
    await expect(list.inRow(latest, 'row-latest-link')).toContainText('2.0.0');
    await expect(list.inRow(latest, 'row-package-link')).toContainText(latest.name);
  });

  // RPS-1261 (5): on the mobile card the "Latest" link used to print the package NAME instead of its latest version.
  test('PKG-pypi-07 the mobile card shows the latest version in its Latest link (RPS-1261)', async ({
    openUiPage,
    adminSession,
    seeder,
    seedVersions,
  }) => {
    const repo = await seeder.createRepo(RepoType.PYPI);
    const [, latest] = await seedVersions(repo, ['1.0.0', '2.0.0']);
    const mobile = await openUiPage({
      session: adminSession,
      viewport: { width: 390, height: 844 },
    });
    const list = protocolPages(mobile, pypi, repo.name).list();
    await list.goto();
    await expect(list.card(latest).getByTestId('row-latest-link')).toContainText('2.0.0');
  });
});
