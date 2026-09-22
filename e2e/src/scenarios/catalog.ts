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
 * The scenario catalog (plan section "Scenario model", table). Every `expect` below was pinned by
 * probing a running instance (`curl` against `./run.sh local up`), not assumed from the plan's
 * table -- see `README.md`'s "Scenario outcomes pinned against a running instance" for the raw
 * evidence and every place a real status differs from what the plan guessed. In short:
 *
 *  - A read-only deploy token attempting a write (`token-ro` publish) gets a plain 401, not a 403:
 *    `ProtocolAuthService.authorizeDeployToken` throws the same `UnAuthorizedException` it throws
 *    for "no credentials at all" (see that class's javadoc on the RPS-939 permission model), and
 *    `MavenAuthPreProcessor` turns every `UnAuthorizedException` from authentication into a 401.
 *  - Rejecting an override, a release version or a snapshot version
 *    (`no-override`/`maven-releases-off`/`maven-snapshots-off` publish) throws
 *    `AccessNotAllowedException`, which `ErrorHandler` maps to 403, not the plan's 409/"conflict".
 *  - The releases/snapshots switches judge a REdeploy of an existing version exactly like a first
 *    deploy (RPS-1174), so `redeploy-releases-off`/`redeploy-snapshots-off` (a version deployed once
 *    while its kind was still allowed, the kind then switched off, the same coordinate deployed
 *    again) are refused with the same 403 (`releaseVersionsAreProhibited`/
 *    `snapshotVersionsAreProhibited`) on the first file of the deploy, and what was already
 *    published stays consumable. Before RPS-1174 that redeploy went through with 200.
 *  - A normal SNAPSHOT redeploy is not an override, even with `allowOverride: false`: Maven writes
 *    new timestamped files (buildNumber + 1) on every deploy and re-uploads the two metadata files,
 *    and the server neither counts a new timestamped file as an existing one nor ever judges
 *    metadata for override (`snapshot-redeploy-no-override`: publish and consume both succeed).
 *    Only re-uploading a file that already exists (the same timestamped name) is an override; that
 *    is pinned at the protocol level in `tests/maven/upload-rules.spec.ts`, since a real client
 *    never does it.
 *  - `reuseCoordinates` marks the scenarios about a redeploy: the fixture pre-publishes (admin,
 *    permissive defaults) the same coordinate the scenario's own publish targets, and only then
 *    applies the scenario's `repo` settings. Every other pre-publish lands on a separate coordinate.
 *  - cargo (step 3b) has no override rule at all: re-publishing an existing version is refused
 *    unconditionally with 400 (`rejected`) by `CargoCrateServiceImpl.checkExistsVersion`, reached via
 *    the publish handler's catch-all (`cargo-raw.ts`'s file header) -- `allowOverride` is settable
 *    and visible for a cargo repo (the panel exposes it) but the protocol never reads it. Both
 *    `no-override` and `override` therefore pin `expectByProtocol: { cargo: { publish: 'rejected' }
 *    }` below instead of getting a cargo-only scenario or a `protocols` restriction: `override`
 *    staying `rejected` for cargo is deliberate, documenting that `allowOverride: true` does NOT make
 *    cargo accept a version override. The real `cargo publish` client never even sends that PUT (its
 *    own client-side `verify_unpublished` preflight refuses first, exit 101) -- only the loop's raw
 *    probe (`clients/cargo.ts`) reaches the server's rule at all.
 *  - nuget (step 3c) is the first protocol with a REAL override rule and a genuine `409 Conflict`:
 *    `AbstractNuGetProtocolFacade.publish` refuses `!allowOverride && versionExists` with `409`
 *    (`nuget-raw.ts`'s file header), so `no-override` pins `expectByProtocol: { nuget: { publish:
 *    'conflict' } }` below, and `override` needs no nuget override at all (its shared `expect` of
 *    `ok` already matches). `checkVersionAllowance` runs BEFORE that override check and answers `422`
 *    (`Outcome` `rejected`) whenever the version's kind (release/prerelease, by whether it contains a
 *    `-`) is switched off by the repo's `releases`/`snapshots` settings -- unlike maven, this applies
 *    identically to a first deploy AND a redeploy, so nuget is added to the `protocols` list of
 *    `maven-releases-off`/`maven-snapshots-off`/`redeploy-releases-off`/`redeploy-snapshots-off`
 *    (still maven-prefixed ids for JUnit/HTML report stability; the prefix is now historical) with
 *    `expectByProtocol: { nuget: { publish: 'rejected' } }`, and the redeploy pair is `422`, never
 *    `409`, for nuget specifically (the kind check runs first). `snapshot-deploy`/
 *    `snapshot-redeploy*` stay maven-only: they are about Maven's timestamped-SNAPSHOT-file
 *    semantics, which nuget has no equivalent of (a nuget "snapshot" is just a version with a `-`
 *    prerelease label, stored once like any other version).
 *  - docker (step 4a) needs NO data changes here at all: every non-maven-restricted scenario applies
 *    unchanged, with the SAME shared `expect` maven already pins -- `no-override`'s 403 ("You cannot
 *    override a version."/`packageOverrideDisabled`, `AbstractDockerProtocolTxFacade
 *    .checkRepoAllowOverride`) matches the shared maven pin byte-for-byte, and every auth failure is
 *    a flat 401 at whichever hop (token exchange or the write/read request itself) a real client
 *    would fail at too -- confirmed live, see `docker-raw.ts`'s file header and README.md's "Docker
 *    runner" section. Docker is never added to `maven-releases-off`/`maven-snapshots-off`/
 *    `redeploy-*-off`/`snapshot-*` (it has no releases/snapshots rule, or a SNAPSHOT-file concept, at
 *    all -- `releases`/`snapshots` repo settings are never read by the Docker protocol).
 *
 * `versionType` matters only to the maven adapter today; other protocols ignore it once they exist.
 */
import type { Scenario } from './types.js';

export const SCENARIOS: readonly Scenario[] = [
  {
    id: 'password-admin',
    tags: ['@smoke', '@auth'],
    repo: { privateRepo: true },
    credential: 'admin-password',
    expect: { publish: 'ok', consume: 'ok' },
  },
  {
    id: 'password-user',
    tags: ['@auth'],
    repo: { privateRepo: true },
    credential: 'user-password',
    expect: { publish: 'ok', consume: 'ok' },
  },
  {
    id: 'token-rw',
    tags: ['@auth'],
    repo: { privateRepo: true },
    credential: 'token-rw',
    expect: { publish: 'ok', consume: 'ok' },
  },
  {
    id: 'token-ro',
    tags: ['@auth', '@negative'],
    repo: { privateRepo: true },
    credential: 'token-ro',
    // Pinned: 401, not the plan's "forbidden" -- see the file-level comment.
    expect: { publish: 'unauthorized', consume: 'ok' },
  },
  {
    id: 'token-expired',
    tags: ['@auth', '@negative'],
    repo: { privateRepo: true },
    credential: 'token-expired',
    expect: { publish: 'unauthorized', consume: 'unauthorized' },
  },
  {
    id: 'token-revoked',
    tags: ['@auth', '@negative'],
    repo: { privateRepo: true },
    credential: 'token-revoked',
    expect: { publish: 'unauthorized', consume: 'unauthorized' },
  },
  {
    id: 'token-rotated-old',
    tags: ['@auth', '@negative'],
    repo: { privateRepo: true },
    credential: 'token-rotated-old',
    expect: { publish: 'unauthorized', consume: 'unauthorized' },
  },
  {
    id: 'token-other-repo',
    tags: ['@auth', '@negative'],
    repo: { privateRepo: true },
    credential: 'token-other-repo',
    expect: { publish: 'unauthorized', consume: 'unauthorized' },
  },
  {
    id: 'wrong-password',
    tags: ['@auth', '@negative'],
    repo: { privateRepo: true },
    credential: 'wrong-password',
    expect: { publish: 'unauthorized', consume: 'unauthorized' },
  },
  {
    id: 'anonymous-private',
    tags: ['@auth', '@negative'],
    repo: { privateRepo: true },
    credential: 'anonymous',
    expect: { publish: 'unauthorized', consume: 'unauthorized' },
  },
  {
    id: 'anonymous-public',
    tags: ['@auth'],
    repo: { privateRepo: false },
    credential: 'anonymous',
    expect: { publish: 'unauthorized', consume: 'ok' },
  },
  {
    id: 'no-override',
    tags: ['@settings', '@negative'],
    repo: { privateRepo: true, allowOverride: false },
    credential: 'token-rw',
    reuseCoordinates: true,
    // Pinned: 403 ("artifactOverrideIsProhibited"), not the plan's "conflict" (409) -- see the
    // file-level comment. cargo: 400 ("rejected") unconditionally -- see the file-level comment's
    // cargo bullet. nuget: a REAL 409 ("conflict") -- the first protocol in this harness to use
    // that outcome for real, see the file-level comment's nuget bullet. docker: the SAME 403
    // ("packageOverrideDisabled") as the shared pin, no override needed -- see the file-level
    // comment's docker bullet.
    expect: { publish: 'forbidden', consume: 'ok' },
    expectByProtocol: { cargo: { publish: 'rejected' }, nuget: { publish: 'conflict' } },
  },
  {
    id: 'override',
    tags: ['@settings'],
    repo: { privateRepo: true, allowOverride: true },
    credential: 'token-rw',
    reuseCoordinates: true,
    expect: { publish: 'ok', consume: 'ok' },
    // cargo has no override rule at all -- see the file-level comment's cargo bullet. Deliberately
    // still `rejected` here even though `allowOverride: true`: that is exactly the point being
    // pinned.
    expectByProtocol: { cargo: { publish: 'rejected' } },
  },
  {
    id: 'maven-releases-off',
    tags: ['@settings', '@negative'],
    repo: { privateRepo: true, releases: false },
    credential: 'token-rw',
    versionType: 'release',
    protocols: ['maven', 'nuget'],
    // Pinned: 403 ("releaseVersionsAreProhibited") for a first deploy of a version that does not
    // exist yet (`redeploy-releases-off` covers an existing one). nuget: 422 ("rejected") --
    // `checkVersionAllowance`, see the file-level comment's nuget bullet.
    expect: { publish: 'forbidden', consume: 'ok' },
    expectByProtocol: { nuget: { publish: 'rejected' } },
  },
  {
    id: 'maven-snapshots-off',
    tags: ['@settings', '@negative'],
    repo: { privateRepo: true, snapshots: false },
    credential: 'token-rw',
    versionType: 'snapshot',
    protocols: ['maven', 'nuget'],
    // Pinned: 403 ("snapshotVersionsAreProhibited") for a first deploy of a version that does not
    // exist yet (`redeploy-snapshots-off` covers an existing one). nuget: 422 ("rejected") -- a
    // nuget "snapshot" is a `-pre` prerelease version, see the file-level comment's nuget bullet.
    expect: { publish: 'forbidden', consume: 'ok' },
    expectByProtocol: { nuget: { publish: 'rejected' } },
  },
  {
    id: 'snapshot-deploy',
    tags: ['@snapshot'],
    repo: { privateRepo: true },
    credential: 'token-rw',
    versionType: 'snapshot',
    protocols: ['maven'],
    // The client deploys timestamped files plus the two metadata files; the consumer resolves the
    // SNAPSHOT through the version-level metadata to the timestamped jar that was deployed.
    expect: { publish: 'ok', consume: 'ok' },
  },
  {
    id: 'snapshot-redeploy',
    tags: ['@snapshot'],
    repo: { privateRepo: true },
    credential: 'token-rw',
    versionType: 'snapshot',
    reuseCoordinates: true,
    protocols: ['maven'],
    // The everyday CI flow: the same SNAPSHOT deployed a second time (buildNumber 2), which the
    // consumer then resolves to the second deploy's jar, not the first.
    expect: { publish: 'ok', consume: 'ok' },
  },
  {
    id: 'snapshot-redeploy-no-override',
    tags: ['@snapshot', '@settings'],
    repo: { privateRepo: true, allowOverride: false },
    credential: 'token-rw',
    versionType: 'snapshot',
    reuseCoordinates: true,
    protocols: ['maven'],
    // Pinned: succeeds. Maven writes new timestamped files, so nothing existing is overridden, and
    // metadata is never judged for override -- see the file-level comment.
    expect: { publish: 'ok', consume: 'ok' },
  },
  {
    id: 'redeploy-snapshots-off',
    tags: ['@settings', '@negative', '@snapshot'],
    repo: { privateRepo: true, allowOverride: true, snapshots: false },
    credential: 'token-rw',
    versionType: 'snapshot',
    reuseCoordinates: true,
    protocols: ['maven', 'nuget'],
    // Pinned (RPS-1174): 403 ("snapshotVersionsAreProhibited") on the first file of the redeploy.
    // The version published while snapshots were still on stays consumable. nuget: 422
    // ("rejected"), never 409 -- `checkVersionAllowance` runs before the override/conflict check.
    expect: { publish: 'forbidden', consume: 'ok' },
    expectByProtocol: { nuget: { publish: 'rejected' } },
  },
  {
    id: 'redeploy-releases-off',
    tags: ['@settings', '@negative'],
    repo: { privateRepo: true, allowOverride: true, releases: false },
    credential: 'token-rw',
    versionType: 'release',
    reuseCoordinates: true,
    protocols: ['maven', 'nuget'],
    // Pinned (RPS-1174): 403 ("releaseVersionsAreProhibited"), as above. nuget: 422 ("rejected"),
    // as above.
    expect: { publish: 'forbidden', consume: 'ok' },
    expectByProtocol: { nuget: { publish: 'rejected' } },
  },
];
