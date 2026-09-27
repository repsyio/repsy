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
 * RPS-1466: an npm publish with no byte in its body (`curl -T empty.json`, or `curl -X PUT
 * --data-binary`, which declares a form content type too, RPS-1443). It used to answer a bare `500`
 * (the JSON reader had no content); it is now `400 npmPublishBodyEmpty` and nothing is stored.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { adminCredential, rawGetPackument, rawPublishBody } from '../../src/clients/npm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const FORM = 'application/x-www-form-urlencoded';

test.describe('npm empty publish body (raw HTTP)', () => {
  for (const contentType of [undefined, 'application/json', FORM]) {
    test(
      `an empty publish (Content-Type ${contentType ?? 'none'}) answers 400 npmPublishBodyEmpty and stores nothing (RPS-1466)`,
      { tag: ['@negative'] },
      async ({ seeder }) => {
        const repo = await seeder.createRepo(RepoType.NPM, { privateRepo: true });
        const admin = adminCredential();
        const name = `e2e-${seeder.runId}-empty`;

        const res = await rawPublishBody(repo.name, admin, name, Buffer.alloc(0), contentType);
        expect(res.status, `answered ${res.status}`).toBe(400);
        expect(res.msgId, 'the error msgId').toBe('npmPublishBodyEmpty');

        const packument = await rawGetPackument(repo.name, admin, name);
        expect(packument.status, 'nothing stored').toBe(404);
      },
    );
  }
});
