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

import { RepoType } from '../api/panel-api.js';

/**
 * One row per repository type the panel can create. REPO-01 (`tests/ui/repositories/create.spec.ts`)
 * is generated from this table, and the dashboard's per-type count rows and the list's type selector
 * are keyed by `slug` too, so a new protocol needs exactly one row here.
 */
export interface UiRepoType {
  /** The API enum value (upper-case on the wire). */
  type: RepoType;
  /**
   * The lower-case key the SPA uses in ids and routes: `selector-option-<slug>`,
   * `repo-count-row-<slug>` and `repo-count-value-<slug>`.
   */
  slug: string;
  /** What the selector button and options display (the slug with a capital, `Maven`). */
  label: string;
  /** REPO-01 is P0 and tagged `@smoke` for this type; the rest are P1. */
  smoke: boolean;
}

function row(type: RepoType, smoke = false): UiRepoType {
  const slug = type.toLowerCase();
  return { type, slug, label: slug.charAt(0).toUpperCase() + slug.slice(1), smoke };
}

export const UI_REPO_TYPES: readonly UiRepoType[] = [
  row(RepoType.MAVEN, true),
  row(RepoType.NPM, true),
  row(RepoType.DOCKER, true),
  row(RepoType.PYPI),
  row(RepoType.CARGO),
  row(RepoType.GOLANG),
  row(RepoType.HELM),
  row(RepoType.NUGET),
  row(RepoType.RUBY),
];

/** The type the create modal preselects when the list is not filtered to one (`RepoType.DOCKER`). */
export const CREATE_MODAL_DEFAULT_TYPE = UI_REPO_TYPES.find(
  ({ type }) => type === RepoType.DOCKER,
)!;

export function uiRepoType(type: RepoType): UiRepoType {
  const found = UI_REPO_TYPES.find((candidate) => candidate.type === type);
  if (!found) {
    throw new Error(`No UI_REPO_TYPES row for ${type}`);
  }
  return found;
}
