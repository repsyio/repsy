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
 * Docker: images -> tags -> (manifests | detail). `target.name` is the image, `target.version` the tag.
 *
 *  - A TAG row click opens the tag DETAIL (`/:image/:tag/detail`); its inner `row-manifests-link` opens
 *    the manifest list (`/:image/:tag`). So `versions.rowOpens` is `detail` and `rowLinks.manifests`
 *    names the link. The tag detail page is where the config digest shows (`pkg-detail-snippet-config`,
 *    only when the image has a config digest).
 *  - The manifest list is read-only: its rows are not clickable and have no delete. Its row key is the
 *    manifest's `name`, which for a single-platform image pushed by tag IS THE TAG (probed: the row is
 *    `pkg-manifests-row-1.0.0`, not the digest); a multi-platform index may name its rows otherwise
 *    (not seeded: the seeder pushes single-platform images). Confirmed in a browser by RPS-1256.
 *  - RPS-1261 (fixed): the manifest table's DESKTOP Digest / Config Digest cells used to show the
 *    platform / the digest; PKG-docker-07 asserts them now. A manifest row is still found by its
 *    `pkg-manifests-row-<key>` id.
 *  - The tag and manifest lists carry the install bar `pkg-install-snippet` above the toolbar; the
 *    image list does not. Detail deletes are titled "Delete Version" but toast "Tag deleted
 *    successfully"; the tag list's own dialog is "Delete Tag".
 */
export const dockerDescriptor: ProtocolDescriptor = {
  protocol: 'docker',
  label: 'Docker',
  levels: {
    list: {
      path: (repo) => `/${repo}`,
      rowKey: (t) => need(t, 'docker').name,
      search: { placeholder: 'image', term: (t) => t.name },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Image', successToast: 'Image deleted successfully' },
      rowOpens: 'versions',
      rowLinks: {},
      installBar: false,
    },
    versions: {
      path: (repo, t) => `/${repo}/${need(t, 'docker').name}`,
      rowKey: (t) => need(t, 'docker').version,
      search: { placeholder: 'tag', term: (t) => t.version },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: { dialogTitle: 'Delete Tag', successToast: 'Tag deleted successfully' },
      rowOpens: 'detail',
      rowLinks: { manifests: 'row-manifests-link' },
      installBar: true,
    },
    manifests: {
      path: (repo, t) => {
        const { name, version } = need(t, 'docker');
        return `/${repo}/${name}/${version}`;
      },
      rowKey: (t) => need(t, 'docker').version,
      search: { placeholder: 'manifest', term: (t) => t.version },
      sort: NEWEST_OLDEST,
      pagination: true,
      mobileCards: true,
      rowDelete: null,
      rowOpens: null,
      rowLinks: {},
      installBar: true,
    },
    detail: {
      path: (repo, t) => {
        const { name, version } = need(t, 'docker');
        return `/${repo}/${name}/${version}/detail`;
      },
      installContains: (repo, t) => [
        `${repo}/${need(t, 'docker').name}:${need(t, 'docker').version}`,
      ],
      repoUrlIn: 'install',
      installTextElement: 'span',
      snippets: ['manifest', 'config'],
      extraIds: ['pkg-detail-version', 'pkg-detail-published'],
      readme: false,
      delete: {
        dialogTitle: 'Delete Version',
        successToast: 'Tag deleted successfully',
        landsOn: 'list',
      },
    },
  },
  lastVersionRemovesPackage: false,
  toolbar: { browseFiles: false },
  configure: {
    title: 'Docker Configuration',
    deployTokenTitle: 'Deploy Token Usage',
    // Docker prints the registry HOST, not the repo URL, and no password placeholder at all.
    contains: (repoName, repoUrl) => [
      `docker login ${new URL(repoUrl).host}`,
      `docker pull ${new URL(repoUrl).host}/${repoName}/`,
    ],
    deployTokenMarker: '<repsy_deploy_token>',
  },
  extraPaths: {},
};
