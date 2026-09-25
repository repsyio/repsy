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

import { NEWEST_OLDEST, need, type ProtocolDescriptor } from './types.js';

/**
 * PyPI: packages -> versions ("releases") -> detail.
 *
 *  - The list row click opens the LATEST version's detail; `row-package-link` opens the versions page,
 *    `row-latest-link` / `row-stable-link` the latest and the latest stable release.
 *  - Deleting a version is called a RELEASE here: the dialog is "Delete Release" both from the
 *    versions page and from the detail page, but both toast "Version deleted successfully".
 *  - The detail page renders `readme` (the upload's long description; RPS-1142 is fixed, PKG-pypi-07
 *    asserts it). Confirmed in a browser by RPS-1256.
 */
export const pypiDescriptor: ProtocolDescriptor = {
  protocol: 'pypi',
  label: 'PyPI',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'pypi').name,
      search: { placeholder: 'package', term: (t) => t.name },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Package', successToast: 'Package deleted successfully' },
      rowOpens: 'detail',
      rowLinks: {
        versions: 'row-package-link',
        detail: 'row-latest-link',
      },
      installBar: false,
    },
    versions: {
      path: (repo, t) => `/${repo}/${need(t, 'pypi').name}`,
      rowKey: (t) => need(t, 'pypi').version,
      search: { placeholder: 'version', term: (t) => t.version },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Release', successToast: 'Version deleted successfully' },
      rowOpens: 'detail',
      rowLinks: {},
      installBar: false,
    },
    detail: {
      path: (repo, t) => {
        const { name, version } = need(t, 'pypi');
        return `/${repo}/${name}/${version}`;
      },
      installContains: (repo, t) => [
        `pip install ${need(t, 'pypi').name}==${need(t, 'pypi').version}`,
        `/${repo}/simple`,
      ],
      repoUrlIn: 'install',
      installTextElement: 'span',
      snippets: [],
      extraIds: [
        'pkg-detail-version',
        'pkg-detail-published',
        'pkg-detail-release-kind',
        'pkg-detail-requires-python',
        'pkg-detail-homepage',
        'pkg-detail-classifiers',
      ],
      readme: true,
      delete: {
        dialogTitle: 'Delete Release',
        successToast: 'Version deleted successfully',
      },
    },
  },
  lastVersionRemovesPackage: true,
  toolbar: { browseFiles: false },
  configure: {
    title: 'Pypi Configuration',
    deployTokenTitle: 'Deploy Token Usage',
    contains: (repoName, repoUrl) => [repoName, `repository=${repoUrl}`],
    passwordMarker: 'YOUR_PASSWORD',
    deployTokenMarker: 'your deploy token',
  },
  extraPaths: {},
};
