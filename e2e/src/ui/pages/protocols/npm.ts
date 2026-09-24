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

import type { PackageRef } from '../../../seed/packages.js';
import { NEWEST_OLDEST, need, type ProtocolDescriptor } from './types.js';

/** The route segment of an unscoped package: `/:repo/~/:package`. */
const UNSCOPED = '~';

/** `@scope/name` -> { scope: 'scope', pkg: 'name' }; `name` -> { scope: undefined, pkg: 'name' }. */
function split(target: PackageRef | undefined): {
  scope: string | undefined;
  pkg: string;
  version: string;
} {
  const { name, version } = need(target, 'npm');
  if (name.startsWith('@')) {
    const slash = name.indexOf('/');
    return { scope: name.slice(1, slash), pkg: name.slice(slash + 1), version };
  }
  return { scope: undefined, pkg: name, version };
}

function packageBase(repo: string, target: PackageRef | undefined): string {
  const { scope, pkg } = split(target);
  return `/${repo}/${scope ?? UNSCOPED}/${pkg}`;
}

/**
 * npm: `/:repo` lists packages (scoped ones show a `@scope` link, unscoped ones sit under `~`), the
 * scope page `/:repo/:scope` lists one scope's packages, then versions, then detail.
 *
 *  - The scope route segment is the scope WITHOUT its `@` (`/:repo/e2e-x/pkg`, probed), `~` when unscoped.
 *  - The list row key is the full name (`@scope/name`, or `name`); the sublist row key is the bare
 *    package name. The list search box has the placeholder `@scope`, and the panel strips a leading
 *    `@` from the query (RPS-1256's PKG-npm-07 asserts it); the query matches the whole row key
 *    (`@scope/name`, RPS-1288 (2)), so the scope, the name or the pair finds a row.
 *  - `rowOpens: 'detail'` on the list and versions pages: a row click goes to the latest version's
 *    detail (list) or that version's detail; `row-package-link` opens the versions page. The scope
 *    link exists only on SCOPED rows (unscoped rows get a plain `row-scope` `~` div instead).
 *  - The versions page has a `pkg-dist-tags` bar (and one `pkg-dist-tag-<tag>` per tag).
 *  - The detail page renders `readme`.
 */
export const npmDescriptor: ProtocolDescriptor = {
  protocol: 'npm',
  label: 'npm',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'npm').name,
      search: {
        placeholder: '@scope',
        term: (t) => (t.name.startsWith('@') ? t.name.slice(0, t.name.indexOf('/')) : t.name),
      },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Package', successToast: 'Package deleted successfully' },
      rowOpens: 'detail',
      rowLinks: {
        sublist: 'row-scope-link',
        versions: 'row-package-link',
        detail: 'row-latest-link',
      },
      installBar: false,
    },
    sublist: {
      path: (repo, t) => `/${repo}/${split(t).scope ?? UNSCOPED}`,
      rowKey: (t) => split(t).pkg,
      search: { placeholder: 'package', term: (t) => t.name.slice(t.name.indexOf('/') + 1) },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Package', successToast: 'Package deleted successfully' },
      rowOpens: 'versions',
      rowLinks: { versions: 'row-package-link', detail: 'row-latest-link' },
      installBar: false,
      siblingName: (t, n) => `@${split(t).scope}/${split(t).pkg}-s${n}`,
    },
    versions: {
      path: (repo, t) => packageBase(repo, t),
      rowKey: (t) => split(t).version,
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
      path: (repo, t) => `${packageBase(repo, t)}/${split(t).version}`,
      installContains: (_repo, t) => [`npm install ${need(t, 'npm').name}`],
      repoUrlIn: 'none',
      installTextElement: 'span',
      snippets: [],
      extraIds: ['pkg-detail-version', 'pkg-detail-published'],
      readme: true,
      delete: {
        dialogTitle: 'Delete Version',
        successToast: 'Version deleted successfully',
        landsOn: 'list',
      },
    },
  },
  lastVersionRemovesPackage: true,
  toolbar: { browseFiles: false },
  configure: {
    title: 'NPM Configuration',
    deployTokenTitle: 'Deploy Token Usage',
    // The URL ends with a slash on purpose (RPS-1206). The modal has no password placeholder: npm asks for it.
    contains: (repoName, repoUrl) => [repoName, `npm login --registry ${repoUrl}/`],
    deployTokenMarker: 'YOUR_DEPLOY_TOKEN',
  },
  extraPaths: {},
};
