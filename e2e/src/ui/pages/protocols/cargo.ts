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

import { NEWEST_OLDEST_NAME, NEWEST_OLDEST, need, type ProtocolDescriptor } from './types.js';

/**
 * Cargo: crates -> versions -> detail. Read from the components, not yet run in a browser (RPS-1257).
 *
 *  - The crate list also sorts by name; a crate row opens the VERSIONS page (no latest-detail shortcut).
 *  - The version list has NO mobile card list (UX-12), so `mobileCards` is false and a mobile-viewport
 *    test must not expect `pkg-versions-card-*` there.
 *  - The detail page renders `readme` and ONE `pkg-detail-install` in either its "Add Dependency" or its
 *    "Install Binary" branch. Cargo has no Package Override setting (irrelevant here).
 */
export const cargoDescriptor: ProtocolDescriptor = {
  protocol: 'cargo',
  label: 'Cargo',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'cargo').name,
      search: { placeholder: 'crate', term: (t) => t.name },
      sort: NEWEST_OLDEST_NAME,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Crate', successToast: 'Crate deleted successfully' },
      rowOpens: 'versions',
      rowLinks: {},
      installBar: false,
    },
    versions: {
      path: (repo, t) => `/${repo}/${need(t, 'cargo').name}`,
      rowKey: (t) => need(t, 'cargo').version,
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
        const { name, version } = need(t, 'cargo');
        return `/${repo}/${name}/${version}`;
      },
      installContains: (_repo, t) => [`${need(t, 'cargo').name}@${need(t, 'cargo').version}`],
      repoUrlIn: 'snippet:cargo-config',
      repoConfigContains: (repo, url) => ['[registries]', `index = "sparse+${url}/${repo}/"`],
      installTextElement: 'span',
      snippets: ['cargo-config', 'cargo-toml'],
      extraIds: ['pkg-detail-version', 'pkg-detail-published'],
      readme: true,
      delete: {
        dialogTitle: 'Delete Version',
        successToast: 'Version deleted successfully',
      },
    },
  },
  lastVersionRemovesPackage: true,
  configure: {
    title: 'Cargo Configuration',
    deployTokenTitle: 'Deploy Token Usage',
    // Both variants render the same body (the token is pasted at cargo's prompt, RPS-1598: no placeholder).
    contains: (repo, url) => [
      repo,
      `sparse+${url}/`,
      'cargo login --registry repsy',
      'paste it and press Enter',
    ],
  },
  toolbar: { browseFiles: false },
  extraPaths: {},
};
