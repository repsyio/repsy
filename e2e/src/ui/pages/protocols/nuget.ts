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
 * NuGet: packages -> versions -> detail. Read from the components, not yet run in a browser (RPS-1257).
 *
 *  - Rows are keyed by the package id. The list sorts by NAME only (`Name (A-Z)` is the default, no
 *    Newest/Oldest).
 *  - The VERSION list has no search box (`search: null`) and no mobile card list (UX-12).
 *  - The install block is the ".NET CLI" one; its text sits in a `<pre>` (`pkg-detail-install-text`).
 *    The detail page also renders `readme`, `pkg-detail-tags`, `pkg-detail-listed` and dependencies.
 *  - Deleting the last version deletes the package, so the detail delete lands on the versions page,
 *    or on the list when it was the last version (`landsOn` records the usual case; unverified).
 */
export const nugetDescriptor: ProtocolDescriptor = {
  protocol: 'nuget',
  label: 'NuGet',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'nuget').name,
      search: { placeholder: 'package', term: (t) => t.name },
      sort: ['Name (A-Z)', 'Name (Z-A)'],
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Package', successToast: 'Package deleted successfully' },
      rowOpens: 'versions',
      rowLinks: {},
      installBar: false,
    },
    versions: {
      path: (repo, t) => `/${repo}/${need(t, 'nuget').name}`,
      rowKey: (t) => need(t, 'nuget').version,
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
        const { name, version } = need(t, 'nuget');
        return `/${repo}/${name}/${version}`;
      },
      installContains: (_repo, t) => [
        `dotnet add package ${need(t, 'nuget').name} --version ${need(t, 'nuget').version}`,
      ],
      repoUrlIn: 'snippet:dotnet-cli-url',
      installTextElement: 'pre',
      snippets: ['package-reference', 'dotnet-cli-url', 'package-manager', 'package-manager-url'],
      extraIds: [
        'pkg-detail-version',
        'pkg-detail-published',
        'pkg-detail-tags',
        'pkg-detail-listed',
        'pkg-detail-dependencies',
      ],
      readme: true,
      delete: {
        dialogTitle: 'Delete Version',
        successToast: 'Version deleted successfully',
        landsOn: 'versions',
        landsOnLast: 'list',
      },
    },
  },
  lastVersionRemovesPackage: true,
  configure: {
    title: 'NuGet Configuration',
    deployTokenTitle: 'Deploy Token Usage',
    // Both variants render the same body; only the title differs.
    contains: (_repo, url) => [`${url}/v3/index.json`, '<YOUR_PASSWORD_OR_DEPLOY_TOKEN>'],
  },
  toolbar: { browseFiles: false },
  extraPaths: {},
};
