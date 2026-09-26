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
 * Matrix row 9 (RPS-1330): `view` / `info`, per client with a view command (`viewCmd`): a full
 * packument GET that the client parses. Asserts what a client reads out of it -- name, dist-tags, the
 * versions, `dist.tarball` (the registry's own address, RPS-1333) and `time` (ISO-8601 UTC that is
 * NOW, not a shifted local time: pnpm's `minimumReleaseAge` and berry's `npmMinimalAgeGate` trust it)
 * -- and pins what the served document also carries:
 *
 * RPS-1357 (fixed): the full packument used to carry `_attachments`, the base64 body of the most
 * recent publish's tarball, and the `_from`/`_resolved` fields npm's `publish <tarball>` adds (a
 * local path of the publisher's machine), in every read, so a package's packument grew by its latest
 * tarball's size on every read and leaked the publisher's file system paths. None of them is kept or
 * served now, like the public registry's packument.
 */
import { rawGetPackument } from '../../../src/clients/npm-raw.js';
import {
  newRepo,
  packageNameFor,
  publishPackage,
  tokenBinding,
} from '../../../src/clients/npm-family/fixtures.js';
import { clientsWith } from '../../../src/clients/npm-family/registry.js';
import { adminCredential } from '../../../src/clients/raw-http.js';
import { repoUrl } from '../../../src/repo-url.js';
import { expect, test } from '../../../src/scenarios/fixtures.js';
import { target } from '../../../src/target.js';

const ISO_UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
// A wrong time zone (the JVM's, with a literal Z) would be off by hours; a remote target's clock is
// not ours to compare with, so there the bound is off.
const MAX_CLOCK_SKEW_MS = target.isRemote ? Number.POSITIVE_INFINITY : 5 * 60 * 1000;

for (const client of clientsWith('viewCmd')) {
  test(
    `${client.label} view reads the packument of a published package`,
    {
      tag: [client.tag, '@view'],
    },
    async ({ seeder }) => {
      const repo = await newRepo(seeder);
      const writer = await tokenBinding(seeder, repo.name, { readOnly: false });
      const ctx = await client.prepare('view', [writer]);
      const name = packageNameFor(seeder, 'viewed');

      const published = await publishPackage(client, ctx, {
        packageName: name,
        version: '1.0.0',
        manifest: { description: 'viewed by the matrix' },
      });
      expect(published.result.exitCode, `publish: ${published.result.command}`).toBe(0);

      const viewed = await client.view?.(ctx, name);
      expect(viewed?.exitCode, `view: ${viewed?.command}\n${viewed?.stderr}`).toBe(0);
      const doc = JSON.parse(viewed?.stdout ?? '{}') as {
        name: string;
        version: string;
        versions: string[];
        'dist-tags': Record<string, string>;
        time: Record<string, string>;
        description: string;
        dist: { tarball: string; integrity: string };
      };

      expect(doc.name).toBe(name);
      expect(doc['dist-tags']).toEqual({ latest: '1.0.0' });
      expect(doc.versions).toEqual(['1.0.0']);
      expect(doc.description).toBe('viewed by the matrix');
      expect(doc.dist.tarball, 'dist.tarball is the registry address (RPS-1333)').toBe(
        repoUrl(repo.name, `${name}/-/${name}-1.0.0.tgz`),
      );

      expect(doc.time['1.0.0'], 'the publish time is ISO-8601 UTC').toMatch(ISO_UTC);
      expect(doc.time.created).toMatch(ISO_UTC);
      expect(doc.time.modified).toMatch(ISO_UTC);
      expect(
        Math.abs(Date.now() - Date.parse(doc.time['1.0.0'] ?? '')),
        'the publish time is now',
      ).toBeLessThan(MAX_CLOCK_SKEW_MS);

      // RPS-1357: what the served full packument no longer carries.
      const raw = await rawGetPackument(repo.name, adminCredential(), name);
      const served = JSON.parse(raw.body.toString('utf8')) as {
        _attachments?: unknown;
        _from?: unknown;
        _resolved?: unknown;
        versions: Record<string, Record<string, unknown>>;
      };
      expect(served._attachments, "RPS-1357: no copy of the latest publish's tarball").toBe(
        undefined,
      );
      expect(served._from ?? served._resolved, 'RPS-1357: no publisher path at the top').toBe(
        undefined,
      );
      const version = served.versions['1.0.0'] ?? {};
      expect(
        version._from ?? version._resolved,
        "RPS-1357: npm's _from/_resolved (the publisher's local tarball path) is not served",
      ).toBe(undefined);
      expect(
        raw.body.toString('utf8'),
        'the tarball is not in the packument, base64 or otherwise',
      ).not.toContain(published.tarball.bytes.toString('base64'));
    },
  );
}
