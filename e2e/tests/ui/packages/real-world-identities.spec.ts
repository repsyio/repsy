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
 * PKG-ID-<case>: package identities of the real world (RPS-1625, gap G13). Every seeder of the UI
 * suite defaults to lower-case, hyphenated names and `1.0.0`; a real registry sees `Newtonsoft.Json`,
 * `My_Package.Name`, `1.2.3+build.5`, `v2.0.0+incompatible`, Maven classifiers, Go module paths with
 * capital letters, very long names. Each case publishes such a package through the wire protocol and
 * asserts what the panel makes of it:
 *
 *  - the list row (keyed by the identity the panel SHOWS, which is not always the published one:
 *    PyPI normalises `My_Package.Name` to `my-package-name`),
 *  - the versions row and the click from it to the detail (the URL the app builds decodes to the
 *    identity: `+`, capital letters, dots and `@scope/` all survive the round trip),
 *  - the detail header (name, version) and the install snippet,
 *  - a reload of that URL, and a direct visit to the route as the descriptor spells it.
 *
 * Data, not code, differs between the cases: `SHOWN` records what the panel displays where it differs
 * from what was published, and `knownFailure` pins a product bug (`test.fail`, never a skip).
 */
import { RepoType } from '../../../src/api/panel-api.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { buildJar, rawPut, versionDir } from '../../../src/clients/maven-raw.js';
import type {
  PackageProtocol,
  PackageRef,
  SeedPackageOptions,
} from '../../../src/seed/packages.js';
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { asDetailPage } from '../../../src/ui/package-scenarios.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

interface IdentityCase {
  /** The test id suffix: `PKG-ID-<id>`. */
  id: string;
  protocol: PackageProtocol;
  /** What the case is about, for the test title. */
  about: string;
  /** What is published. */
  name: string;
  version: string;
  /** What the panel shows for it when that is not what was published. */
  shown?: { name?: string; version?: string };
  /** Seeder options beyond name and version (npm: `scoped`). */
  options?: SeedPackageOptions;
  /** Extra wire uploads after the publish (Maven classifier files). */
  afterPublish?: (repoName: string, published: PackageRef) => Promise<void>;
  /** Another spelling of the name that must reach the same detail page (PyPI's normalised name). */
  alias?: string;
  /** A product bug this case runs into: `RPS-nnnn: what is wrong`. The case runs under `test.fail`. */
  knownFailure?: string;
}

const LONG_NAME = `e2e-long-${'a'.repeat(150)}`;
const LONG_TAG = `v${'9'.repeat(126)}`;

/** Maven classifier jars next to the main one: `-sources`, `-javadoc`, `-tests`. */
async function publishClassifiers(repoName: string, published: PackageRef): Promise<void> {
  const [groupId, artifactId] = published.name.split(':');
  const dir = versionDir(groupId, artifactId, published.version);
  for (const classifier of ['sources', 'javadoc', 'tests']) {
    const res = await rawPut(
      repoName,
      adminCredential(),
      `${dir}/${artifactId}-${published.version}-${classifier}.jar`,
      buildJar({ groupId, artifactId, version: published.version }),
      'application/octet-stream',
    );
    expect(res.status, `PUT the ${classifier} classifier jar`).toBeLessThan(300);
  }
}

const CASES: readonly IdentityCase[] = [
  // npm -----------------------------------------------------------------------------------------
  {
    id: 'npm-scoped-build',
    protocol: 'npm',
    about: 'a scoped package with a pre-release version and build metadata',
    name: '@e2e-scope.one/pkg_build.js',
    version: '1.2.3-beta.4+build.567',
  },
  {
    id: 'npm-long-name',
    protocol: 'npm',
    about: 'an unscoped package with a 150-character name',
    name: LONG_NAME,
    version: '10.20.30-rc.1',
    options: { scoped: false },
  },
  // Cargo ---------------------------------------------------------------------------------------
  {
    id: 'cargo-build',
    protocol: 'cargo',
    about: 'a crate with build metadata',
    name: 'e2e_crate_build',
    version: '1.2.3+build.9',
  },
  {
    id: 'cargo-prerelease',
    protocol: 'cargo',
    about: 'a crate with a pre-release version',
    name: 'e2e_crate_pre',
    version: '0.1.0-alpha.1',
  },
  // NuGet ---------------------------------------------------------------------------------------
  {
    id: 'nuget-pascal',
    protocol: 'nuget',
    about: 'a PascalCase package id',
    name: 'E2e.Newtonsoft.Json',
    version: '13.0.3',
    // The panel keys and shows a NuGet package by its normalised (lower-case) id, like the registry does.
    shown: { name: 'e2e.newtonsoft.json' },
  },
  {
    id: 'nuget-prerelease',
    protocol: 'nuget',
    about: 'a PascalCase id with a pre-release version',
    name: 'E2e.PascalCase.Pkg',
    version: '1.0.0-beta.1',
    shown: { name: 'e2e.pascalcase.pkg' },
  },
  {
    id: 'nuget-four-part',
    protocol: 'nuget',
    about: 'a four-part version',
    name: 'E2e.FourPart',
    version: '1.2.3.4',
    shown: { name: 'e2e.fourpart' },
  },
  {
    id: 'nuget-build-metadata',
    protocol: 'nuget',
    about: 'a version with build metadata (NuGet ignores it: 1.0.0+meta.1 is 1.0.0)',
    name: 'E2e.BuildMeta',
    version: '1.0.0+meta.1',
    shown: { name: 'e2e.buildmeta', version: '1.0.0' },
  },
  // PyPI ----------------------------------------------------------------------------------------
  {
    id: 'pypi-normalised',
    protocol: 'pypi',
    about: 'a name that PEP 503 normalises (My_Package.Name -> my-package-name)',
    name: 'My_Package.Name',
    version: '1.0.0rc1',
    // The panel keeps the name as published; the normalised spelling (what pip and the Simple index use) is a link too.
    alias: 'my-package-name',
  },
  {
    id: 'pypi-post',
    protocol: 'pypi',
    about: 'a post release',
    name: 'e2e-pypi-post',
    version: '1.0.post1',
  },
  {
    id: 'pypi-dev',
    protocol: 'pypi',
    about: 'a calendar-versioned dev release',
    name: 'e2e-pypi-dev',
    version: '2024.1.0.dev3',
  },
  // Maven ---------------------------------------------------------------------------------------
  {
    id: 'maven-classifiers',
    protocol: 'maven',
    about:
      'capital letters in the group and artifact, a release candidate, sources/javadoc/tests jars',
    name: 'Io.Repsy.E2e.Upper:Some-Artifact_1',
    version: '1.0.0-RC1',
    afterPublish: publishClassifiers,
  },
  {
    id: 'maven-dotted-version',
    protocol: 'maven',
    about: 'a dotted qualifier version',
    name: 'io.repsy.e2e.dotted:dotted-art',
    version: '2.0.0.Final',
  },
  // Go ------------------------------------------------------------------------------------------
  {
    id: 'go-capitals',
    protocol: 'go',
    about: 'a module path with capital letters',
    name: 'e2e.repsy.test/Foo/BarBaz',
    version: 'v1.2.3',
  },
  {
    id: 'go-incompatible',
    protocol: 'go',
    about: 'a +incompatible version',
    name: 'e2e.repsy.test/e2e-incompat/lib',
    version: 'v2.0.0+incompatible',
  },
  {
    id: 'go-pseudo',
    protocol: 'go',
    about: 'a pseudo-version',
    name: 'e2e.repsy.test/e2e-pseudo/lib',
    version: 'v0.0.0-20240101000000-abcdef123456',
  },
  {
    id: 'go-unicode',
    protocol: 'go',
    about: 'a module path with non-ASCII letters (Go allows them in a path element)',
    name: 'e2e.repsy.test/e2e-üñí/módulo',
    version: 'v1.0.0',
  },
  // Helm ----------------------------------------------------------------------------------------
  {
    id: 'helm-build',
    protocol: 'helm',
    about: 'a chart version with build metadata (a `+` an OCI tag stores as `_`)',
    name: 'e2e-chart-build',
    version: '1.2.3+build.5',
  },
  {
    id: 'helm-prerelease',
    protocol: 'helm',
    about: 'a chart with a pre-release version',
    name: 'e2e-chart-pre',
    version: '1.0.0-rc.1',
  },
  // Ruby ----------------------------------------------------------------------------------------
  {
    id: 'ruby-caps-pre',
    protocol: 'ruby',
    about: 'a gem name with capitals and a pre-release version',
    name: 'E2e-Mixed_Gem',
    version: '1.0.0.pre.1',
  },
  {
    id: 'ruby-beta',
    protocol: 'ruby',
    about: 'a beta version',
    name: 'e2e_beta_gem',
    version: '2.0.0.beta1',
  },
  // Docker --------------------------------------------------------------------------------------
  {
    id: 'docker-tag-chars',
    protocol: 'docker',
    about: 'a tag with capitals, dots, underscores and hyphens',
    name: 'e2e-tags',
    version: 'V1.0.0_RC-1.x',
  },
  {
    id: 'docker-long-tag',
    protocol: 'docker',
    about: 'a 127-character tag',
    name: 'e2e-longtag',
    version: LONG_TAG,
  },
];

test.describe('Package identities of the real world', { tag: '@packages' }, () => {
  for (const identity of CASES) {
    const descriptor = DESCRIPTORS[identity.protocol];
    const pinned = identity.knownFailure ? ` [known failure: ${identity.knownFailure}]` : '';
    test(`PKG-ID-${identity.id} ${identity.about}: list, versions, detail and the URL round trip${pinned}`, async ({
      adminPage,
      seeder,
      seedPackage,
    }) => {
      test.fail(Boolean(identity.knownFailure), identity.knownFailure);
      const repo = await seeder.createRepo(
        RepoType[identity.protocol.toUpperCase() as keyof typeof RepoType],
      );
      const published = await seedPackage(repo, {
        ...identity.options,
        name: identity.name,
        version: identity.version,
      });
      await identity.afterPublish?.(repo.name, published);
      // A second version of the same package, so that a search by version has something to filter out.
      await seedPackage(repo, {
        ...identity.options,
        name: identity.name,
        version: identity.protocol === 'go' ? 'v9.9.9' : '9.9.9',
      });

      // What the panel shows: the published identity unless the case records a normalisation.
      const shown: PackageRef = {
        ...published,
        name: identity.shown?.name ?? published.name,
        version: identity.shown?.version ?? published.version,
      };
      // The row and the header show the artifact of `group:artifact` and the package of `@scope/package`.
      const displayName = shown.name.split(/[:/]/).pop() ?? shown.name;
      const pages = protocolPages(adminPage, descriptor, repo.name);

      // The package list has exactly one row for it, and the row names it.
      const list = pages.list();
      await list.goto();
      await list.expectRow(shown);
      await expect(list.rows()).toHaveCount(1);
      await expect(list.row(shown)).toContainText(displayName);

      // The versions page has one row per version (the classifier jars and the like are no versions).
      const versions = pages.versions(shown);
      await versions.goto();
      await versions.expectRow(shown);
      await expect(versions.rows()).toHaveCount(2);
      await expect(versions.row(shown)).toContainText(shown.version);

      // The search finds it by its version, whatever characters that has (`+` is a space in a query).
      if (descriptor.levels.versions.search) {
        await versions.searchFor(shown);
        await expect(versions.rows()).toHaveCount(1);
        await versions.expectRow(shown);
        await versions.search('');
        await expect(versions.rows()).toHaveCount(2);
      }

      // The row opens the detail at the URL the app builds, and that URL decodes to the identity.
      const detail = asDetailPage(await versions.openRow(shown));
      await detail.expectLoaded();
      await expect(detail.error).toHaveCount(0);
      await expect(detail.root).toBeVisible();
      const opened = new URL(adminPage.url());
      const decodedPath = decodeURIComponent(`${opened.pathname}${opened.search}`);
      expect(decodedPath).toContain(decodeURIComponent(detail.path()).split('#')[0]);

      // The header names the package and the version; the install snippet carries both.
      const expectDetail = async (): Promise<void> => {
        await expect(detail.root).toBeVisible();
        await expect(detail.name).toContainText(displayName);
        await expect(detail.root).toContainText(shown.version);
        for (const part of descriptor.levels.detail.installContains(repo.name, shown)) {
          await expect(detail.installText).toContainText(part);
        }
      };
      await expectDetail();

      // A reload of the URL the app built shows the same page.
      await adminPage.reload();
      await detail.expectLoaded();
      await expect(detail.error).toHaveCount(0);
      await expectDetail();

      // So does a direct visit to the route as the descriptor spells it.
      await detail.goto();
      await expect(detail.error).toHaveCount(0);
      await expectDetail();

      // Another spelling of the name reaches the same package.
      if (identity.alias) {
        const aliased = pages.detail({ ...shown, name: identity.alias });
        await aliased.goto();
        await expect(aliased.error).toHaveCount(0);
        await expect(aliased.root).toBeVisible();
        await expect(aliased.root).toContainText(shown.version);
      }
    });
  }
});
