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
 * Helm: charts -> versions -> detail. Read from the components, not yet run in a browser (RPS-1257).
 *
 *  - Helm has TWO backend modules behind ONE panel: `oci` (OCI registry, `helm push oci://`) and
 *    `classic` (ChartMuseum HTTP API). Both feed the same chart list, so a seeded chart of either
 *    kind renders identically; `seedVariants` names them for `SeedPackageOptions.variant`.
 *  - A chart row opens the VERSIONS page; `row-latest-link` opens the latest version's detail.
 *  - The version list pages CLIENT-SIDE (the API returns every version of the chart): ten per page.
 *  - The install block is the "Classic Helm" one, its text in a `<pre>`; the detail page has extra
 *    `pkg-detail-type` and `pkg-detail-digest` and two snippets (`oci`, `chart-yaml`).
 */
export const helmDescriptor: ProtocolDescriptor = {
  protocol: 'helm',
  label: 'Helm',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'helm').name,
      search: { placeholder: 'chart', term: (t) => t.name },
      sort: NEWEST_OLDEST_NAME,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Chart', successToast: 'Chart deleted successfully' },
      rowOpens: 'versions',
      rowLinks: { detail: 'row-latest-link' },
      installBar: false,
    },
    versions: {
      path: (repo, t) => `/${repo}/${need(t, 'helm').name}`,
      rowKey: (t) => need(t, 'helm').version,
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
        const { name, version } = need(t, 'helm');
        return `/${repo}/${name}/${version}`;
      },
      installContains: (repo, t) => [
        `helm install ${need(t, 'helm').name} ${repo}/${need(t, 'helm').name} --version ${need(t, 'helm').version}`,
      ],
      repoUrlIn: 'snippet:oci',
      installTextElement: 'pre',
      snippets: ['oci', 'chart-yaml'],
      extraIds: [
        'pkg-detail-version',
        'pkg-detail-published',
        'pkg-detail-type',
        'pkg-detail-digest',
      ],
      readme: false,
      delete: {
        dialogTitle: 'Delete Version',
        successToast: 'Version deleted successfully',
      },
    },
  },
  lastVersionRemovesPackage: true,
  configure: {
    title: 'Helm Configuration',
    deployTokenTitle: 'Deploy Token Usage',
    // Both variants render the same body; only the title differs.
    contains: (repo, url) => [
      `helm repo add ${repo} ${url}`,
      '<YOUR_PASSWORD_OR_DEPLOY_TOKEN>',
      `oci://${new URL(url).host}/${repo}`,
    ],
  },
  toolbar: { browseFiles: false },
  extraPaths: {},
  seedVariants: ['oci', 'classic'],
};
