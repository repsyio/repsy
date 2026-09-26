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
 * RPS-1482: two limits of the HTTP connector itself, on the wire (README.md "Size-limit leg").
 *
 *  - An oversized request header is refused by Tomcat before Repsy's code runs: a 400 with Tomcat's own
 *    HTML page, on both ports, with no Repsy JSON envelope (`msgId`). The limit is Tomcat's default
 *    8 KiB for the whole header block, whether it is one large header or many small ones (probed:
 *    an 8100-byte header passes, an 8300-byte one is refused). It needs no overlay, so this part is
 *    `@smoke` and runs on every stack.
 *  - A CHUNKED upload (`Transfer-Encoding: chunked`, no `Content-Length`, what a streaming client
 *    sends) is limited by the bytes that really arrive, not by a header a client could leave out: a gem
 *    and a Go module zip over `RUBY_MAX_GEM_SIZE` / `GO_MAX_MODULE_ZIP_SIZE` are answered with 413 and
 *    the `payloadTooLarge` envelope and store nothing, and the same upload under the limit is
 *    accepted (so chunked is not refused wholesale). It needs the limits overlay (`@limits`, skips
 *    itself without it, `./run.sh local up --limits`).
 */
import { randomUUID } from 'node:crypto';

import { RepoType } from '../../src/api/panel-api.js';
import { apiUrl, edgeRequest, repoUrl } from '../../src/clients/edge-raw.js';
import { oversizeWorld, SIZE_LIMIT_SCENARIO } from '../../src/clients/oversize.js';
import {
  OVER_LIMIT_PADDING_BYTES,
  SIZE_LIMIT_BYTES,
  UNDER_LIMIT_PADDING_BYTES,
} from '../../src/clients/padding.js';
import { golangAdapter } from '../../src/clients/golang.js';
import { buildModuleZip, uploadUrl as goUploadUrl } from '../../src/clients/golang-raw.js';
import { authHeader } from '../../src/clients/raw-http.js';
import { rubyAdapter } from '../../src/clients/ruby.js';
import {
  buildGem,
  publishUrl as rubyPublishUrl,
  rawAuthHeaderFor,
} from '../../src/clients/ruby-raw.js';
import type { ProtocolAdapter } from '../../src/scenarios/adapter.js';
import { expect, materializeCredentialKind, test } from '../../src/scenarios/fixtures.js';
import { expectPayloadTooLarge } from '../../src/scenarios/size-limits.js';
import { optedIn } from '../../src/stack-overlays.js';
import { target } from '../../src/target.js';

/** Tomcat's default `maxHttpHeaderSize` is 8 KiB for the whole header block. */
const UNDER_HEADER_LIMIT = 4_000;
const OVER_HEADER_LIMIT = 9_000;

/** A wire path per port whose plain answer is JSON: the repo port has no `/api`, the panel port wants
 *  a login. */
const HEADER_TARGETS = [
  { name: 'the repo port', url: () => repoUrl('/api/users'), plain: 404, msgId: 'unknownPath' },
  { name: 'the api port', url: () => apiUrl('/api/users'), plain: 401, msgId: undefined },
] as const;

test.describe('an oversized request header', { tag: '@smoke' }, () => {
  for (const port of HEADER_TARGETS) {
    test(`is refused by the connector with a 400 HTML page on ${port.name}`, async () => {
      const control = await edgeRequest(port.url(), {
        headers: { 'X-Padding': 'a'.repeat(UNDER_HEADER_LIMIT) },
      });
      expect(control.status, 'control: a 4 KB header is served as usual').toBe(port.plain);
      expect(control.headers.get('content-type')).toContain('application/json');

      const refused = await edgeRequest(port.url(), {
        headers: { 'X-Padding': 'a'.repeat(OVER_HEADER_LIMIT) },
      });
      expect(refused.status, `a ${OVER_HEADER_LIMIT}-byte header: ${refused.text}`).toBe(400);
      expect(refused.headers.get('content-type')).toContain('text/html');
      expect(refused.text, "Tomcat's own page").toContain('HTTP Status 400');
      expect(
        refused.json,
        'not Repsy’s JSON envelope: the request never reaches Repsy',
      ).toBeUndefined();
    });

    test(`counts the whole header block, many small headers included, on ${port.name}`, async () => {
      const many: Record<string, string> = {};
      for (let n = 0; n < 120; n += 1) {
        many[`X-Padding-${n}`] = 'b'.repeat(80);
      }
      const refused = await edgeRequest(port.url(), { headers: many });
      expect(refused.status, `120 headers of 80 bytes: ${refused.text}`).toBe(400);
      expect(refused.headers.get('content-type')).toContain('text/html');
    });
  }
});

/** `bytes` as a chunked request body (a stream has no length, so `fetch` sends `Transfer-Encoding:
 *  chunked`) in pieces of 8 KiB. */
function chunkedBody(bytes: Buffer): ReadableStream<Uint8Array> {
  const piece = 8 * 1024;
  let offset = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset >= bytes.length) {
        controller.close();
        return;
      }
      controller.enqueue(new Uint8Array(bytes.subarray(offset, offset + piece)));
      offset += piece;
    },
  });
}

async function chunkedRequest(
  url: string,
  method: 'POST' | 'PUT',
  headers: Record<string, string>,
  bytes: Buffer,
): Promise<{ status: number; body: Buffer }> {
  const res = await fetch(url, {
    method,
    headers,
    body: chunkedBody(bytes),
    duplex: 'half',
  } as RequestInit);
  return { status: res.status, body: Buffer.from(await res.arrayBuffer()) };
}

interface ChunkedCase {
  protocol: string;
  repoType: RepoType;
  adapter: ProtocolAdapter;
  /** The upload to the route of the protocol: over the limit with `padBytes` padding. */
  upload: (
    world: ReturnType<typeof oversizeWorld>,
    padBytes: number,
  ) => Promise<{ status: number; body: Buffer; bytes: number }>;
}

const CHUNKED_CASES: ChunkedCase[] = [
  {
    protocol: 'ruby',
    repoType: RepoType.RUBY,
    adapter: rubyAdapter,
    upload: async (world, padBytes) => {
      const built = await buildGem({
        name: world.publishTarget.packageName,
        version: world.publishTarget.version,
        marker: randomUUID(),
        padBytes,
      });
      const res = await chunkedRequest(
        rubyPublishUrl(world.repoName),
        'POST',
        { ...rawAuthHeaderFor(world.credential), 'Content-Type': 'application/octet-stream' },
        built.bytes,
      );
      return { ...res, bytes: built.bytes.length };
    },
  },
  {
    protocol: 'golang',
    repoType: RepoType.GOLANG,
    adapter: golangAdapter,
    upload: async (world, padBytes) => {
      const built = await buildModuleZip({
        modulePath: world.publishTarget.packageName,
        version: world.publishTarget.version,
        padBytes,
      });
      const res = await chunkedRequest(
        goUploadUrl(world.repoName, built.modulePath, built.version),
        'PUT',
        {
          ...authHeader(world.credential),
          'Content-Sha256': built.sha256Hex,
          'Content-Type': 'application/zip',
        },
        built.bytes,
      );
      return { ...res, bytes: built.bytes.length };
    },
  },
];

test.describe('a chunked upload against the size limits', { tag: '@limits' }, () => {
  // eslint-disable-next-line playwright/no-skipped-test -- the local-only guard: the overlay changes Repsy's own settings
  test.skip(!target.ownsStack || target.isRemote, 'needs a stack this harness owns');
  // eslint-disable-next-line playwright/no-skipped-test -- the opt-in switch of README.md "Stack overlays"
  test.skip(
    !optedIn('limits'),
    'opt-in: needs the limits overlay; ./run.sh local up --limits, then REPSY_E2E_LIMITS=1 ./run.sh test --protocol api --grep @limits',
  );

  for (const chunked of CHUNKED_CASES) {
    async function freshWorld(seeder: Parameters<typeof materializeCredentialKind>[0]) {
      const repo = await seeder.createRepo(chunked.repoType, { privateRepo: true });
      const credential = await materializeCredentialKind(
        seeder,
        'token-rw',
        repo.name,
        chunked.repoType,
      );
      return oversizeWorld(chunked.protocol, repo.name, credential, {
        packageName: chunked.adapter.packageName(seeder.runId, SIZE_LIMIT_SCENARIO),
        version: chunked.adapter.version('release'),
      });
    }

    test(`${chunked.protocol} > a chunked upload over the limit is 413 and stores nothing`, async ({
      seeder,
    }) => {
      const world = await freshWorld(seeder);
      const before = await chunked.adapter.fingerprint(world);

      const res = await chunked.upload(world, OVER_LIMIT_PADDING_BYTES);

      expect(res.bytes, 'the package is over the limit').toBeGreaterThan(SIZE_LIMIT_BYTES);
      expectPayloadTooLarge({ status: res.status, body: res.body });
      await chunked.adapter.expectNothingStored(world, before);
    });

    test(`${chunked.protocol} > a chunked upload under the limit is accepted`, async ({
      seeder,
    }) => {
      const world = await freshWorld(seeder);
      const before = await chunked.adapter.fingerprint(world);

      const res = await chunked.upload(world, UNDER_LIMIT_PADDING_BYTES);

      expect(res.bytes, 'the package is under the limit').toBeLessThan(SIZE_LIMIT_BYTES);
      expect(res.status, `chunked upload: ${res.body.toString('utf8')}`).toBeLessThan(300);
      const after: unknown = await chunked.adapter.fingerprint(world);
      expect(after, 'the package is stored').not.toEqual(before);
    });
  }
});
