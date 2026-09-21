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
    // Pinned: 403 ("artifactOverrideIsProhibited"), not the plan's "conflict" (409) -- see the
    // file-level comment.
    expect: { publish: 'forbidden', consume: 'ok' },
  },
  {
    id: 'override',
    tags: ['@settings'],
    repo: { privateRepo: true, allowOverride: true },
    credential: 'token-rw',
    expect: { publish: 'ok', consume: 'ok' },
  },
  {
    id: 'maven-releases-off',
    tags: ['@settings', '@negative'],
    repo: { privateRepo: true, releases: false },
    credential: 'token-rw',
    versionType: 'release',
    protocols: ['maven'],
    // Pinned: 403 ("releaseVersionsAreProhibited").
    expect: { publish: 'forbidden', consume: 'ok' },
  },
  {
    id: 'maven-snapshots-off',
    tags: ['@settings', '@negative'],
    repo: { privateRepo: true, snapshots: false },
    credential: 'token-rw',
    versionType: 'snapshot',
    protocols: ['maven'],
    // Pinned: 403 ("snapshotVersionsAreProhibited").
    expect: { publish: 'forbidden', consume: 'ok' },
  },
];
