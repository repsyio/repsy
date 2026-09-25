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

import type { PackageRef } from '../../../seed/packages.js';
import { type ProtocolDescriptor, NEWEST_OLDEST, need } from './types.js';

/** `group:artifact` -> [group, artifact]. */
function coordinates(target: PackageRef | undefined): {
  group: string;
  artifact: string;
  version: string;
} {
  const { name, version } = need(target, 'maven');
  const [group, artifact] = name.split(':');
  return { group, artifact, version };
}

/**
 * Maven: groups -> artifacts -> versions -> detail, plus the file browser.
 *
 *  - `list` (`/:repo`) is the GROUP list, but its rows are one per ARTIFACT, keyed `group:artifact`;
 *    deleting from it deletes the whole GROUP ("Delete Group"; RPS-1288 (4): the dialog names the group
 *    and counts the artifacts and versions that go). Its row click opens the latest
 *    version's detail; `row-group-link` opens `/:repo/:group` and `row-artifact-link` the versions.
 *  - `sublist` (`/:repo/:group`) lists that group's artifacts, keyed by the bare artifactId.
 *  - Every list page has a "Browse Files" button (`pkg-browse-files`, `/:repo/browser`).
 *  - The detail page's install block is the Apache Maven one: `pkg-detail-install-text` is on the
 *    highlighted `<code>`. RPS-1261 (fixed): its Gradle Groovy snippet used to show the
 *    Grape snippet (a duplicate); PKG-maven-07 asserts the Gradle one. All values here were
 *    confirmed in a browser (RPS-1256).
 *  - The detail page has no version badge and no "published" line, so those ids do not exist here.
 */
export const mavenDescriptor: ProtocolDescriptor = {
  protocol: 'maven',
  label: 'Maven',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'maven').name,
      search: { placeholder: 'group', term: (t) => t.name.split(':')[0] },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Group', successToast: 'Group deleted successfully' },
      rowOpens: 'detail',
      rowLinks: {
        sublist: 'row-group-link',
        versions: 'row-artifact-link',
        detail: 'row-latest-link',
      },
      installBar: false,
    },
    sublist: {
      path: (repo, t) => `/${repo}/${coordinates(t).group}`,
      rowKey: (t) => coordinates(t).artifact,
      search: { placeholder: 'artifact', term: (t) => t.name.split(':')[1] },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Artifact', successToast: 'Artifact deleted successfully' },
      rowOpens: 'detail',
      rowLinks: { versions: 'row-artifact-link', detail: 'row-latest-link' },
      installBar: false,
      siblingName: (t, n) => `${coordinates(t).group}:${coordinates(t).artifact}-s${n}`,
    },
    versions: {
      path: (repo, t) => {
        const c = coordinates(t);
        return `/${repo}/${c.group}/${c.artifact}`;
      },
      rowKey: (t) => coordinates(t).version,
      search: { placeholder: 'version', term: (t) => t.version },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Version', successToast: 'Version deleted successfully' },
      rowOpens: 'detail',
      rowLinks: {},
      installBar: false,
    },
    detail: {
      path: (repo, t) => {
        const c = coordinates(t);
        return `/${repo}/${c.group}/${c.artifact}/${c.version}`;
      },
      installContains: (_repo, t) => {
        const c = coordinates(t);
        return [
          `<groupId>${c.group}</groupId>`,
          `<artifactId>${c.artifact}</artifactId>`,
          `<version>${c.version}</version>`,
        ];
      },
      repoUrlIn: 'snippet:repository',
      repoConfigContains: (repo, url) => [`<url>${url}/${repo}</url>`, '<repositories>'],
      installTextElement: 'code',
      snippets: [
        'pom',
        'repository',
        'gradle-groovy',
        'gradle-kotlin',
        'sbt',
        'ivy',
        'grape',
        'leiningen',
        'buildr',
        'purl',
        'bazel',
      ],
      extraIds: ['pkg-detail-meta-group', 'pkg-detail-meta-artifact', 'pkg-detail-meta-signed'],
      readme: false,
      delete: {
        dialogTitle: 'Delete Version',
        successToast: 'Version deleted successfully',
      },
    },
  },
  lastVersionRemovesPackage: true,
  toolbar: { browseFiles: true },
  configure: {
    title: 'Maven Configuration',
    deployTokenTitle: 'Deploy Token Usage',
    contains: (repoName, repoUrl) => [repoName, `<url>${repoUrl}</url>`, 'mvn compile deploy'],
    passwordMarker: 'YOUR_PASSWORD',
    deployTokenMarker: 'YOUR_DEPLOY_TOKEN',
  },
  extraPaths: { browser: (repo) => `/${repo}/browser` },
};
