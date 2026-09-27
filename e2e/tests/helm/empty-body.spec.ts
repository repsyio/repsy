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
 * RPS-1466: a classic Helm chart upload with an empty chart part (`400 helmChartEmpty`, it was a `500`
 * from the gzip reader), and one that is not multipart at all, as `curl -X POST --data-binary
 * @chart.tgz` sends it (a `400` like a missing part; the servlet container refused to look for parts in
 * it, a `500`). The OCI side is pinned by the backend's integration tests: an empty manifest is
 * `400 manifestInvalidJson`, and the empty blob is a valid blob that is stored.
 */
import { RepoType } from '../../src/api/panel-api.js';
import {
  adminCredential,
  parseIndex,
  rawGetIndex,
  rawPostChartBody,
  rawUploadChart,
} from '../../src/clients/helm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

/** The names in the classic index of the repo; none while the index is not there. */
async function chartNames(repoName: string): Promise<string[]> {
  const index = await rawGetIndex(repoName, adminCredential());
  return index.status === 200 ? Object.keys(parseIndex(index.body.toString('utf8')).entries) : [];
}

test.describe('helm empty chart upload (raw HTTP)', () => {
  test(
    'an empty chart part answers 400 helmChartEmpty and stores nothing (RPS-1466)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
      const admin = adminCredential();

      const res = await rawUploadChart(repo.name, admin, Buffer.alloc(0), 'empty-0.1.0.tgz');
      expect(res.status, `answered ${res.status}`).toBe(400);
      expect(res.msgId, 'the error msgId').toBe('helmChartEmpty');

      expect(await chartNames(repo.name), 'nothing stored').toEqual([]);
    },
  );

  for (const contentType of ['application/x-www-form-urlencoded', 'application/octet-stream']) {
    test(
      `a chart posted without multipart (Content-Type ${contentType}) answers 400, not 500 (RPS-1466)`,
      { tag: ['@negative'] },
      async ({ seeder }) => {
        const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
        const admin = adminCredential();

        const res = await rawPostChartBody(
          repo.name,
          admin,
          Buffer.from('not a chart'),
          contentType,
        );
        expect(res.status, `answered ${res.status}`).toBe(400);
        expect(res.body.toString('utf8')).toBe("Missing 'chart' part");
      },
    );
  }
});
