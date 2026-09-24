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

import { NEWEST_OLDEST, NEWEST_OLDEST_NAME, need, type ProtocolDescriptor } from './types.js';

/**
 * Ruby: gems -> versions -> detail. Read from the components, not yet run in a browser (RPS-1257).
 *
 *  - A gem row opens the VERSIONS page; `row-latest-link` opens the latest version's detail.
 *  - The detail page's `pkg-detail-yanked` badge shows after a yank; the install block is "Install"
 *    (`pkg-detail-snippet-gemfile` is the Gemfile line). A confirmed detail delete navigates `../..`
 *    (the list), unverified.
 */
export const rubyDescriptor: ProtocolDescriptor = {
  protocol: 'ruby',
  label: 'Ruby',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'ruby').name,
      search: { placeholder: 'gem', term: (t) => t.name },
      sort: NEWEST_OLDEST_NAME,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Gem', successToast: 'Gem deleted successfully' },
      rowOpens: 'versions',
      rowLinks: { detail: 'row-latest-link' },
      installBar: false,
    },
    versions: {
      path: (repo, t) => `/${repo}/${need(t, 'ruby').name}`,
      rowKey: (t) => need(t, 'ruby').version,
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
        const { name, version } = need(t, 'ruby');
        return `/${repo}/${name}/${version}`;
      },
      installContains: (_repo, t) => [
        `gem install ${need(t, 'ruby').name} -v ${need(t, 'ruby').version}`,
      ],
      repoUrlIn: 'install',
      installTextElement: 'span',
      snippets: ['gemfile'],
      extraIds: [
        'pkg-detail-version',
        'pkg-detail-published',
        'pkg-detail-yanked',
        'pkg-detail-runtime-deps',
        'pkg-detail-dev-deps',
      ],
      readme: false,
      delete: {
        dialogTitle: 'Delete Version',
        successToast: 'Version deleted successfully',
        landsOn: 'list',
      },
    },
  },
  lastVersionRemovesPackage: true,
  configure: {
    // Ruby's modal has no deploy-token branch: the title and the body are the same in both variants.
    title: 'Ruby Configuration',
    deployTokenTitle: 'Ruby Configuration',
    contains: (repo, url) => [`source "${url}" do`, `gem push your_gem-1.0.0.gem --host ${url}`],
  },
  toolbar: { browseFiles: false },
  extraPaths: {},
};
