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
/**
 * RPS-1466: a Cargo publish whose body has no byte in it, stops inside a length field, or carries a
 * crate of zero bytes. Each used to answer a bare `500` (the buffer underflow of the length read) and is
 * now a `400` in Cargo's own error shape, with nothing stored. `curl -X PUT --data-binary` declares a
 * form content type too (RPS-1443), which must change nothing.
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  buildPublishBody,
  cargoErrorDetail,
  parseIndex,
  rawGetIndex,
  rawPublish,
} from '../../src/clients/cargo-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const FORM = 'application/x-www-form-urlencoded';

test.describe('cargo empty publish body (raw HTTP)', () => {
  for (const contentType of ['application/octet-stream', FORM]) {
    test(
      `a publish with no crate in it (Content-Type ${contentType}) answers a cargo-shaped 400 and stores nothing (RPS-1466)`,
      { tag: ['@negative'] },
      async ({ seeder }) => {
        const repo = await seeder.createRepo(RepoType.CARGO, { privateRepo: true });
        const admin = adminCredential();
        const name = `e2e_${seeder.runId.replace(/-/g, '_')}_empty`;

        const cases: { label: string; body: Buffer; detail: string }[] = [
          { label: 'no byte', body: Buffer.alloc(0), detail: 'the publish body is empty' },
          {
            label: 'two bytes',
            body: Buffer.from([1, 0]),
            detail: 'the publish body ends before a length field is complete',
          },
          {
            label: 'a crate of zero bytes',
            body: buildPublishBody({ name, version: '1.0.0', crateBytes: Buffer.alloc(0) }),
            detail: 'the crate is empty',
          },
        ];

        for (const { label, body, detail } of cases) {
          const res = await rawPublish(repo.name, admin, body, { contentType });
          expect(res.status, `${label}: answered ${res.status}`).toBe(400);
          expect(cargoErrorDetail(res.body), `${label}: the error detail`).toBe(detail);
        }

        const index = await rawGetIndex(repo.name, admin, name);
        expect(index.status === 404 ? [] : parseIndex(index.body), 'nothing stored').toEqual([]);
      },
    );
  }
});
