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
 * RPS-1466: a gem push made with `curl -X POST --data-binary @x.gem` declares
 * `Content-Type: application/x-www-form-urlencoded`, and Tomcat reads such a `POST` body into request
 * parameters as soon as anything calls `getParameter` before the handler reads the stream. Nothing
 * on the Ruby route does, so the gem is stored byte for byte: this is the test that would fail if a
 * filter or a pre-processor ever did (MockMvc cannot reproduce Tomcat's parsing, so the backend's own
 * integration test cannot see it). A push with no byte in its body is refused with `400 invalidGemFile`.
 */
import { ERROR_CODES } from '../../src/error-codes.js';
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  buildGem,
  gemFilename,
  msgIdOf,
  namesRelPath,
  parseNames,
  rawDownload,
  rawGet,
  rawPublish,
} from '../../src/clients/ruby-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const FORM = 'application/x-www-form-urlencoded';

test.describe('ruby form-typed and empty gem push (raw HTTP)', () => {
  test(
    'a gem pushed as a form-typed POST is stored byte for byte (RPS-1466)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
      const admin = adminCredential();
      const name = `e2e-${seeder.runId}-form`.toLowerCase();
      const built = await buildGem({ name, version: '1.0.0' });

      const pushed = await rawPublish(repo.name, admin, built.bytes, { contentType: FORM });
      expect(pushed.status, `answered ${pushed.status} (msgId ${msgIdOf(pushed.body)})`).toBe(200);

      const downloaded = await rawDownload(repo.name, admin, gemFilename(name, '1.0.0'));
      expect(downloaded.status).toBe(200);
      expect(downloaded.body.equals(built.bytes), 'the stored gem is what was pushed').toBe(true);
    },
  );

  for (const contentType of ['application/octet-stream', FORM]) {
    test(
      `a push with no byte in its body (Content-Type ${contentType}) answers 400 invalidGemFile and stores nothing (RPS-1466)`,
      { tag: ['@negative'] },
      async ({ seeder }) => {
        const repo = await seeder.createRepo(RepoType.RUBY, { privateRepo: true });
        const admin = adminCredential();

        const res = await rawPublish(repo.name, admin, Buffer.alloc(0), { contentType });
        expect(res.status, `answered ${res.status}`).toBe(400);
        expect(msgIdOf(res.body), 'the error msgId').toBe(ERROR_CODES.INVALID_GEM_FILE);

        const names = await rawGet(repo.name, admin, namesRelPath());
        expect(names.status).toBe(200);
        expect(parseNames(names.body), 'nothing stored').toEqual([]);
      },
    );
  }
});
