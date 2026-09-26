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
 * A fake Repsy Cloud target for the skeleton (RPS-1498), on top of the in-memory `FakePanelBackend`:
 * what a cloud backend module supplies beyond the panel operations, so `cloud-target.spec.ts` can
 * exercise the target seam of the scenario engine (skip, expectation overlay, known gap) without a
 * server. It is also a worked example for a real Repsy Cloud backend module:
 *
 *  - `FAKE_CLOUD_CAPABILITIES`: the capabilities of the target under test (a real run gets them from
 *    `src/target.ts` by `REPSY_TARGET=cloud-...`; the fake overrides one to make a skip reachable);
 *  - `expectByTarget`: outcomes that differ from the catalog's;
 *  - `knownGap`: a scenario side expected to fail, with the ticket that tracks it;
 *  - `seedUserCredential` / `seedExpiredTokenCredential`: the credentials that differ per product.
 */
import {
  type DeployTokenForm,
  type DeployTokenInfoListItem,
  type PanelBackend,
  type ScenarioSide,
  type TokenInfo,
  UnsupportedPanelOperation,
} from '../../src/api/panel-backend.js';
import type { ExpectationOverlay } from '../../src/scenarios/types.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import { capabilitiesFor, type TargetCapabilities } from '../../src/target.js';
import { FakePanelBackend } from './fake-panel-backend.js';

/** The ticket the fake names for the credential it cannot seed. */
export const FAKE_CLOUD_UNSUPPORTED_TICKET = 'RPS-0001';
/** The ticket the fake pins its known gaps to. */
export const FAKE_CLOUD_GAP_TICKET = 'RPS-0002';

/** `cloud-local`'s capabilities, except that no expired token can be seeded at all. */
export const FAKE_CLOUD_CAPABILITIES: TargetCapabilities = {
  ...capabilitiesFor('cloud-local'),
  expiredTokenStrategy: 'unsupported',
};

/** The scenario ids `cloud-target.spec.ts` registers, and what the fake cloud pins for each. */
export const FAKE_CLOUD_OVERLAY: ExpectationOverlay = {
  // The catalog says a read-only token's publish is `forbidden`; this cloud answers 401 for every protocol.
  'fake-overlay': { '*': { publish: 'unauthorized' } },
  // A protocol-specific entry beats the `*` one.
  'fake-overlay-protocol': {
    '*': { publish: 'unauthorized' },
    maven: { publish: 'conflict' },
  },
};

/** A scenario side that is expected to fail on this cloud, and the side it is. */
export const FAKE_CLOUD_GAPS: Readonly<Record<string, ScenarioSide>> = {
  'fake-gap-publish': 'publish',
  'fake-gap-consume': 'consume',
};

export class FakeCloudPanelBackend extends FakePanelBackend implements PanelBackend {
  readonly expectByTarget = FAKE_CLOUD_OVERLAY;
  /** Every deploy token created: the repo it belongs to and its list item. */
  readonly tokens = new Map<string, { repoName: string; item: DeployTokenInfoListItem }>();
  private nextTokenId = 1;

  knownGap(protocol: string, scenarioId: string, side: ScenarioSide): string | undefined {
    return FAKE_CLOUD_GAPS[scenarioId] === side
      ? `${FAKE_CLOUD_GAP_TICKET}: the fake cloud gets ${protocol} ${scenarioId} wrong on the ${side} side`
      : undefined;
  }

  // A repo user of a cloud is a collaborator with a plan limit: the fake has none to offer.
  async seedUserCredential(): Promise<MaterializedCredential> {
    throw new UnsupportedPanelOperation(FAKE_CLOUD_UNSUPPORTED_TICKET, 'seedUserCredential');
  }

  // Never reached while `FAKE_CLOUD_CAPABILITIES.expiredTokenStrategy` is `unsupported`: the fixture
  // decides from the capabilities before it asks the backend.
  async seedExpiredTokenCredential(): Promise<MaterializedCredential> {
    throw new Error('seedExpiredTokenCredential must not be called for an unsupported strategy');
  }

  override async createDeployToken(repoName: string, form: DeployTokenForm): Promise<TokenInfo> {
    const id = `fake-token-${this.nextTokenId++}`;
    this.tokens.set(id, {
      repoName,
      item: { id, name: form.name, readOnly: form.readOnly ?? false } as DeployTokenInfoListItem,
    });
    return { username: `user-${id}`, token: `secret-${id}` } as TokenInfo;
  }

  override async findDeployTokenByName(
    repoName: string,
    name: string,
  ): Promise<DeployTokenInfoListItem | undefined> {
    return [...this.tokens.values()].find(
      (token) => token.repoName === repoName && token.item.name === name,
    )?.item;
  }

  override async revokeDeployToken(_repoName: string, tokenId: string): Promise<void> {
    this.tokens.delete(tokenId);
  }
}

/** The factory contract of `REPSY_E2E_BACKEND_MODULE`. */
export function createPanelBackend(baseUrl: string): PanelBackend {
  return new FakeCloudPanelBackend(baseUrl);
}
