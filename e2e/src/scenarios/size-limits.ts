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

/**
 * The size-limit specs of one protocol (RPS-1482, README.md "Size-limit leg"): what a real client and
 * the server do with a package over the upload limit, on a stack started with the limits overlay
 * (`./run.sh local up --limits`, `docker-compose.stack-limits.yml`: 64 KiB on every configurable
 * limit). `registerSizeLimitSpecs` registers two tests per client, tagged `@limits`, that skip
 * themselves without `optedIn('limits')` (the nightly `limits` leg fails on a skip):
 *
 *  - OVER: one push over the limit with the real client. The client fails (exit code and a stable
 *    part of the message it prints), the raw replay of the same package answers what the server
 *    really sends (413 and the `payloadTooLarge` envelope, or the protocol's own error shape), and the
 *    adapter's `fingerprint` shows the repository exactly as it was (README.md "Nothing stored").
 *  - UNDER: the same client pushes a package of real weight (20 KB) that fits, and it is stored. It
 *    proves the limit is what refused the first push, not a client or a repository that cannot
 *    publish at all.
 *
 * The per-client parts (how to push, what the client prints, what the raw answer looks like) come from
 * `clients/oversize.ts` and the caller; the assertions are the same for every protocol.
 */
import { RepoType } from '../api/panel-api.js';
import type { OversizePush } from '../clients/oversize.js';
import { oversizeWorld, SIZE_LIMIT_SCENARIO } from '../clients/oversize.js';
import {
  OVER_LIMIT_PADDING_BYTES,
  SIZE_LIMIT_BYTES,
  UNDER_LIMIT_PADDING_BYTES,
} from '../clients/padding.js';
import type { RawResponse } from '../clients/raw-http.js';
import { optedIn } from '../stack-overlays.js';
import { target } from '../target.js';
import type { ProtocolAdapter } from './adapter.js';
import { expect, materializeCredentialKind, test } from './fixtures.js';
import type { World } from './world.js';

/** The envelope of Repsy's own 413 (`ErrorHandler`, `payloadTooLarge`). */
export const PAYLOAD_TOO_LARGE = {
  status: 413,
  msgId: 'payloadTooLarge',
  text: 'The uploaded content is too large.',
} as const;

/** Asserts the raw answer is Repsy's 413 envelope: a JSON body with `msgId` and the human text. */
export function expectPayloadTooLarge(replay: RawResponse): void {
  expect(replay.status, `raw replay status; body: ${replay.body.toString('utf8')}`).toBe(
    PAYLOAD_TOO_LARGE.status,
  );
  const body = JSON.parse(replay.body.toString('utf8')) as Record<string, unknown>;
  expect(body.msgId, 'msgId').toBe(PAYLOAD_TOO_LARGE.msgId);
  expect(body.type, 'type').toBe('ERROR');
  expect(body.text, 'text').toBe(PAYLOAD_TOO_LARGE.text);
}

export interface SizeLimitSpecs<F> {
  /** Lower-case protocol, for titles and `world.protocol` (`pypi`, `helm-classic`, ...). */
  protocol: string;
  /** What the client is called in titles (`twine`, `dotnet nuget push`, ...). */
  client: string;
  repoType: RepoType;
  adapter: ProtocolAdapter<F>;
  push: (world: World, padBytes: number, label: string) => Promise<OversizePush>;
  /** A stable part of what the client prints for the refusal (exit code aside). */
  clientMessage: RegExp;
  /** How the raw replay of the over-limit package must look; the default is Repsy's 413 envelope. */
  expectReplay?: (replay: RawResponse) => void;
}

export function registerSizeLimitSpecs<F>(spec: SizeLimitSpecs<F>): void {
  const { protocol, adapter } = spec;

  test.describe(`${protocol} size limits (${spec.client})`, { tag: '@limits' }, () => {
    test.skip(!target.ownsStack || target.isRemote, 'needs a stack this harness owns');
    test.skip(
      !optedIn('limits'),
      'opt-in: needs the limits overlay; ./run.sh local up --limits, then REPSY_E2E_LIMITS=1 ./run.sh test --protocol <runner> --grep @limits',
    );

    async function freshWorld(
      seeder: Parameters<typeof materializeCredentialKind>[0],
    ): Promise<World> {
      const repo = await seeder.createRepo(spec.repoType, { privateRepo: true });
      const credential = await materializeCredentialKind(
        seeder,
        'token-rw',
        repo.name,
        spec.repoType,
      );
      return oversizeWorld(protocol, repo.name, credential, {
        packageName: adapter.packageName(seeder.runId, SIZE_LIMIT_SCENARIO),
        version: adapter.version('release'),
      });
    }

    test(
      `${protocol} > ${spec.client}: a package over the limit is refused with 413 and nothing is stored`,
      {
        tag: '@negative',
      },
      async ({ seeder }) => {
        const world = await freshWorld(seeder);
        const before = await adapter.fingerprint(world);

        const pushed = await spec.push(
          world,
          OVER_LIMIT_PADDING_BYTES,
          `${protocol}-oversize-${seeder.runId}`,
        );

        expect(
          pushed.packageBytes,
          'the package really is over the limit (the test would prove nothing otherwise)',
        ).toBeGreaterThan(SIZE_LIMIT_BYTES);
        expect(
          pushed.exitCode,
          `${spec.client} must fail for an over-limit package: ${pushed.command}\n${pushed.output}`,
        ).not.toBe(0);
        expect(pushed.output, `what ${spec.client} prints for the refusal`).toMatch(
          spec.clientMessage,
        );
        (spec.expectReplay ?? expectPayloadTooLarge)(pushed.replay);

        await adapter.expectNothingStored(world, before);
      },
    );

    test(`${protocol} > ${spec.client}: a package just under the limit is stored`, async ({
      seeder,
    }) => {
      const world = await freshWorld(seeder);
      const before = await adapter.fingerprint(world);

      const pushed = await spec.push(
        world,
        UNDER_LIMIT_PADDING_BYTES,
        `${protocol}-undersize-${seeder.runId}`,
      );

      expect(pushed.packageBytes, 'the package is under the limit').toBeLessThan(SIZE_LIMIT_BYTES);
      expect(
        pushed.exitCode,
        `${spec.client} must publish a package that fits: ${pushed.command}\n${pushed.output}`,
      ).toBe(0);
      expect(
        pushed.replay.status,
        'a raw replay of a package that fits is not size-limited',
      ).not.toBe(413);
      const after: unknown = await adapter.fingerprint(world);
      expect(after, 'the package that fits is in the repository').not.toEqual(before);
    });
  });
}
