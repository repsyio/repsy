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
 * Go: modules -> versions -> detail, but the routes carry the module path as a QUERY PARAMETER
 * (`golang.org/x/mod` has slashes), not as path segments. Read from the components, not yet run in
 * a browser (RPS-1257).
 *
 *  - versions:  `/:repo/modules?modulePath=<path>`
 *  - detail:    `/:repo/modules/version?modulePath=<path>&version=<v>`
 *  - The list row key is the raw module path (`golang.org/x/mod`); the breadcrumb shows the module path.
 *  - A versions row click also appends `#security`. The version list's pagination sits OUTSIDE the
 *    list container (UX-15), which does not change its ids.
 *  - The detail page's install block is the "go get" one; the endpoints block is `pkg-detail-goproxy`
 *    (one `pkg-detail-goproxy-<label>` per endpoint). A confirmed detail delete navigates to
 *    `/:repo/modules?modulePath=` (the versions page), so `landsOn` is `versions` (unverified).
 *  - A version that does not exist renders `pkg-error` with "Version '<v>' not found".
 *  - Go has no Package Override setting.
 */
export const golangDescriptor: ProtocolDescriptor = {
  protocol: 'golang',
  label: 'Go',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'golang').name,
      search: { placeholder: 'module', term: (t) => t.name },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Module', successToast: 'Module deleted successfully' },
      rowOpens: 'versions',
      rowLinks: {},
      installBar: false,
    },
    versions: {
      path: (repo, t) =>
        `/${repo}/modules?modulePath=${encodeURIComponent(need(t, 'golang').name)}`,
      rowKey: (t) => need(t, 'golang').version,
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
        const { name, version } = need(t, 'golang');
        return `/${repo}/modules/version?modulePath=${encodeURIComponent(name)}&version=${encodeURIComponent(version)}`;
      },
      installContains: (repo, t) => [
        `/${repo},off go get ${need(t, 'golang').name}@${need(t, 'golang').version}`,
      ],
      repoUrlIn: 'install',
      installTextElement: 'span',
      snippets: ['go-env'],
      extraIds: ['pkg-detail-version', 'pkg-detail-published', 'pkg-detail-goproxy'],
      readme: false,
      delete: {
        dialogTitle: 'Delete Version',
        successToast: 'Version deleted successfully',
        landsOn: 'versions',
      },
    },
  },
  lastVersionRemovesPackage: false,
  configure: {
    title: 'Golang Configuration',
    deployTokenTitle: 'Deploy Token Usage',
    contains: (repo, url) => [`GOPROXY="${url}`, `${url}/\${MODULE_PATH}/@v/\${VERSION}.zip`],
    passwordMarker: 'YOUR_PASSWORD',
    deployTokenMarker: 'YOUR_DEPLOY_TOKEN',
  },
  toolbar: { browseFiles: false },
  extraPaths: {},
};
