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
 * RPS-1466: a Go upload whose body has no byte in it. `curl -T empty.zip` sends `Content-Length: 0`,
 * `curl -X PUT --data-binary @empty.zip` declares a form content type as well (RPS-1443: Spring's form
 * filter used to read such a body before the handler saw it). No module zip is empty, so the answer is
 * `400 goModuleZipEmpty` and nothing is stored.
 */
import { ERROR_CODES } from '../../src/error-codes.js';
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  listRelPath,
  msgIdOf,
  parseVersionList,
  rawGet,
  rawPut,
  uploadRelPath,
} from '../../src/clients/go-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const FORM = 'application/x-www-form-urlencoded';

test.describe('go empty upload body (raw HTTP)', () => {
  for (const contentType of [undefined, 'application/zip', FORM]) {
    test(
      `an empty module zip (Content-Type ${contentType ?? 'none'}) answers 400 goModuleZipEmpty and stores nothing (RPS-1466)`,
      { tag: ['@negative'] },
      async ({ seeder }) => {
        const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
        const modulePath = `example.com/e2e-${seeder.runId}-empty`;
        const admin = adminCredential();

        const res = await rawPut(
          repo.name,
          admin,
          uploadRelPath(modulePath, 'v0.0.1'),
          Buffer.alloc(0),
          { contentType },
        );
        expect(res.status, `answered ${res.status}`).toBe(400);
        expect(msgIdOf(res.body), 'the error msgId').toBe(ERROR_CODES.GO_MODULE_ZIP_EMPTY);

        const list = await rawGet(repo.name, admin, listRelPath(modulePath));
        expect(list.status === 404 ? [] : parseVersionList(list.body), 'nothing stored').toEqual(
          [],
        );
      },
    );
  }
});
