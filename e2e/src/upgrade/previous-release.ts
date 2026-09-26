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

/**
 * The release the upgrade-path leg starts from (RPS-1487, README.md "Upgrade path"): the image
 * `repo.repsy.io/repsy/os/repsy:<PREVIOUS_RELEASE>`, pullable anonymously. It is the ONE place that names
 * the tag: `tests/stack/upgrade.spec.ts` imports it and `run.sh local up --upgrade` reads the line below
 * with `sed`, so keep its exact shape (`export const PREVIOUS_RELEASE = '<tag>';`).
 *
 * BUMP IT AFTER EACH RELEASE, to the newest tag that is published (the Git tags are `v26.08.x`, the image
 * tags have no `v`). The spec's expectations hold from that release on: the password reset of V0017 and the
 * Docker manifest migration of V0024 only apply to a release before them, so a release that already has
 * both would need the spec's per-release expectations revisited (README.md "Upgrade path").
 * `REPSY_E2E_UPGRADE_FROM=<tag>` overrides it for one run.
 *
 * AFTER A RELEASE (releases are cut by hand, no checklist lists this: README.md "Upgrade path", "After a
 * release", RPS-1600): once the new tag's image is pullable, change the line below, read the spec's
 * per-release expectations again (`PREVIOUS_SCHEMA_VERSION`, V0017, V0024) and run
 * `gh workflow run e2e-nightly.yml -f suite=upgrade`.
 */
export const PREVIOUS_RELEASE = '26.08.4';

/** The published image of a release tag. */
export function releaseImage(tag: string): string {
  return `repo.repsy.io/repsy/os/repsy:${tag}`;
}
