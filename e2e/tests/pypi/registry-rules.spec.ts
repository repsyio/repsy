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
 * The pypi server's registry rules, pinned at the protocol level with raw HTTP POSTs/GETs (no
 * `twine`/`pip` client), the pypi analogue of `tests/nuget/registry-rules.spec.ts`/
 * `tests/cargo/registry-rules.spec.ts`. Every status/detail here was read from
 * `AbstractPypiProtocolFacade`/`AbstractPypiStorageService`/`PackageStorageUtils`/`ReleaseVersion`/
 * `PypiAuthPreProcessor` first and then confirmed against a running instance (see `pypi-raw.ts`'s
 * file header and `README.md`'s "PyPI runner" section for the raw evidence and every H-number these
 * tests reference).
 *
 * Six backend bug candidates were found and confirmed live while building this suite, each filed as
 * its own Jira story per this repo's e2e process. RPS-1221/RPS-1223/RPS-1224/RPS-1225/RPS-1226 are
 * now FIXED (see `AbstractPypiProtocolFacade.uploadPackage`/`AbstractPypiStorageService`,
 * `PypiPackageServiceImpl.getPackageList`/`packages.ftl`/`PypiSimpleHandlerPreProcessor`,
 * `AbstractPypiHeadProtocolMethodHandler`) and their tests below pin the corrected behaviour; the
 * rest are still open:
 *  - **RPS-1222** (fixed): the panel's own PyPI config screen used to tell users
 *    `repository=${baseUrl}/${repoName}/simple` for `.pypirc`, but the upload handler only matches
 *    the repo ROOT -- `twine upload -r <that source>` 404s (`unknownPath`). The panel now omits the
 *    `/simple` suffix from the `repository=` line (`pypi-config.component.ts`); `/simple` remains the
 *    correct, read-only "simple index" path (used for `pip`'s `--extra-index-url`), so `POST` there
 *    still 404s by design, unrelated to the panel copy fix.
 *  - **RPS-1223** (fixed): `checkOverridePermission` used to compare the FORM `version` against the
 *    version RE-EXTRACTED from the filename instead of the filename itself, so a mismatched form
 *    `version` made an existing file overwritable even under `allowOverride: false`. `isPackageFileExist`
 *    is now decided by the filename alone.
 *  - **P4**: storage-before-DB (the RPS-1124 family already open for cargo/nuget; commented there, not
 *    a new ticket): `writePackageArchive` runs before `ReleaseVersion.of(form.version)` can still
 *    throw `badVersionString`, leaving an orphaned, downloadable archive+sidecar with no DB row.
 *  - **RPS-1224/RPS-1225** (fixed): the `.sha256` sidecar used to be the client-sent `sha256_digest`
 *    VERBATIM, never recomputed or verified -- a missing digest used to be an unhandled `500`
 *    (RPS-1224), a wrong one used to be silently served to every consumer (RPS-1225). The facade now
 *    rejects a missing digest with `400 sha256DigestMissing`, computes the SHA-256 of the actual
 *    uploaded bytes, rejects a mismatch with `400 sha256DigestMismatch` (case-insensitively), and
 *    stores/serves only the server-computed, lowercased value -- all before any storage write.
 *  - **RPS-1226** (fixed): `HEAD` on any path under a pypi repo used to answer `200` unconditionally,
 *    existence never checked. `AbstractPypiHeadProtocolMethodHandler` now mirrors `GET`'s status via
 *    existence-only facade lookups (a non-normalized project name mirrors `GET`'s `307` redirect too).
 */
import { RepoType } from '../../src/api/panel-api.js';
import { pypiAdapter } from '../../src/clients/pypi.js';
import {
  adminCredential,
  authHeader,
  buildWheel,
  distName,
  downloadPath,
  msgIdOf,
  packageName as rawPackageName,
  parseSimplePage,
  parseSimpleRoot,
  rawDownload,
  rawGetSimplePage,
  rawGetSimplePageNoFollow,
  rawGetSimpleRoot,
  rawHead,
  rawUpload,
  sha256Hex,
  simplePagePath,
  simpleRootPath,
  uploadUrl,
  wheelFilename,
  type RawResponse,
} from '../../src/clients/pypi-raw.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  packageName: string;
}

/** A fresh pypi repo (permissive defaults) and a run-unique package name for it. */
async function newRepo(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
  return { repoName: repo.name, packageName: `e2e-${seeder.runId}-${label}` };
}

function expectMsgId(res: RawResponse, status: number, msgId: string | undefined): void {
  expect(res.status, `answered ${res.status} (msgId ${msgIdOf(res.body) ?? 'none'})`).toBe(status);
  if (msgId !== undefined) {
    expect(msgIdOf(res.body), 'the error msgId').toBe(msgId);
  }
}

test.describe('pypi registry rules (raw HTTP)', () => {
  test(
    'a read-only deploy token publish is refused with a flat 401 (not 403), and the same token ' +
      'can still read the project page (H5)',
    { tag: ['@auth', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'roauth');
      const token = await seeder.createToken(layout.repoName, { readOnly: true });
      const credential = {
        transport: 'basic' as const,
        username: token.username,
        password: token.token,
        kind: 'token' as const,
      };
      const version = pypiAdapter.version('release');
      const built = buildWheel({ name: layout.packageName, version });

      const res = await rawUpload(layout.repoName, credential, built);
      expectMsgId(res, 401, 'unAuthorized');

      // Real Repsy panel envelope (not a bodyless 401), with the Basic challenge header every
      // other protocol in this harness gives too.
      expect(res.msgId, 'msgId').toBe('unAuthorized');

      // Seed a file with admin so the read side has something to read.
      const seedRes = await rawUpload(layout.repoName, adminCredential(), built);
      expectMsgId(seedRes, 200, undefined);

      const readRes = await rawGetSimplePage(layout.repoName, credential, layout.packageName);
      expect(readRes.status, 'the read-only token can still READ').toBe(200);
    },
  );

  test(
    'a non-multipart POST to the repo root is 404 unknownPath (no handler matches at all); a ' +
      'multipart POST without a "content" part is a bodyless 400 (H11)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'badupload');
      const admin = adminCredential();

      const jsonRes = await fetch(uploadUrl(layout.repoName), {
        method: 'POST',
        headers: { ...authHeader(admin), 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      const jsonBytes = Buffer.from(await jsonRes.arrayBuffer());
      expectMsgId({ status: jsonRes.status, body: jsonBytes }, 404, 'unknownPath');

      const form = new FormData();
      form.append('name', 'not-a-real-upload');
      const noContentRes = await fetch(uploadUrl(layout.repoName), {
        method: 'POST',
        headers: authHeader(admin),
        body: form,
      });
      const noContentBytes = Buffer.from(await noContentRes.arrayBuffer());
      expect(noContentRes.status, 'a multipart POST with no "content" part').toBe(400);
      expect(noContentBytes, 'the 400 is bodyless').toHaveLength(0);
    },
  );

  test(
    'POST /<repo> without a trailing slash is accepted like POST /<repo>/; POST /<repo>/simple ' +
      'still 404s by design -- the panel no longer instructs that URL (RPS-1222, H12)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'notrailingslash');
      const admin = adminCredential();
      const version = pypiAdapter.version('release');
      const built = buildWheel({ name: layout.packageName, version });

      const noSlashUrl = `${env.repoBaseUrl}/${layout.repoName}`;
      const form = new FormData();
      form.append('name', built.name);
      form.append('version', built.version);
      form.append('requires_python', built.requiresPython);
      form.append('sha256_digest', built.sha256Hex);
      form.append('content', new Blob([new Uint8Array(built.bytes)]), built.filename);
      const res = await fetch(noSlashUrl, {
        method: 'POST',
        headers: authHeader(admin),
        body: form,
      });
      const bytes = Buffer.from(await res.arrayBuffer());
      expectMsgId({ status: res.status, body: bytes }, 200, undefined);

      // RPS-1222 (fixed): the panel's config screen used to point `.pypirc`'s `repository` at
      // `<baseUrl>/<repo>/simple` -- exactly the URL a real `twine upload -r <source>` built from it
      // would POST to, which 404s. The panel now omits the `/simple` suffix (`repository=<baseUrl>/
      // <repo>`, matching `noSlashUrl` above), so a user following the CURRENT instructions never
      // hits this path. `/simple` itself remains the read-only "simple index" route (used for `pip`'s
      // `--extra-index-url`, see `installation` in `pypi-packages-version-detail.component.ts`), so
      // `POST` there is correctly refused -- this is no longer a bug, just documented behaviour.
      const simpleIndexUrl = `${env.repoBaseUrl}/${layout.repoName}/simple`;
      const built2 = buildWheel({
        name: layout.packageName,
        version: pypiAdapter.version('release'),
      });
      const form2 = new FormData();
      form2.append('name', built2.name);
      form2.append('version', built2.version);
      form2.append('requires_python', built2.requiresPython);
      form2.append('sha256_digest', built2.sha256Hex);
      form2.append('content', new Blob([new Uint8Array(built2.bytes)]), built2.filename);
      const simpleRes = await fetch(simpleIndexUrl, {
        method: 'POST',
        headers: authHeader(admin),
        body: form2,
      });
      const simpleBytes = Buffer.from(await simpleRes.arrayBuffer());
      expectMsgId({ status: simpleRes.status, body: simpleBytes }, 404, 'unknownPath');
    },
  );

  test(
    'an archive filename that does not match NAME-VERSION(-tag)*.(tar.gz|whl|zip) is refused ' +
      'with 400 archiveFileNameInvalid',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'badfilename');
      const admin = adminCredential();
      const version = pypiAdapter.version('release');
      const built = buildWheel({ name: layout.packageName, version });

      const form = new FormData();
      form.append(':action', 'file_upload');
      form.append('name', built.name);
      form.append('version', built.version);
      form.append('requires_python', built.requiresPython);
      form.append('sha256_digest', built.sha256Hex);
      // "v" prefix on the version is not the PEP 440 CANONICAL grammar the filename pattern
      // requires, even though ReleaseVersion.of would happily normalize it elsewhere.
      form.append(
        'content',
        new Blob([new Uint8Array(built.bytes)]),
        `${distName(built.name)}-v${version}-py3-none-any.whl`,
      );
      const res = await fetch(uploadUrl(layout.repoName), {
        method: 'POST',
        headers: authHeader(admin),
        body: form,
      });
      const bytes = Buffer.from(await res.arrayBuffer());
      expectMsgId({ status: res.status, body: bytes }, 400, 'archiveFileNameInvalid');
    },
  );

  test(
    'override is refused per FILENAME under allowOverride:false, regardless of what the form ' +
      'version says (RPS-1223)',
    { tag: ['@settings', '@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'override');
      const admin = adminCredential();
      const version = pypiAdapter.version('release');
      const builtA = buildWheel({ name: layout.packageName, version, marker: 'v1' });
      expectMsgId(await rawUpload(layout.repoName, admin, builtA), 200, undefined);

      await seeder.setSettings(layout.repoName, {
        privateRepo: true,
        allowOverride: false,
      });

      const filename = wheelFilename(layout.packageName, version);
      const dlBefore = await rawDownload(layout.repoName, admin, layout.packageName, filename);
      expect(dlBefore.status, 'the seeded wheel downloads').toBe(200);
      expect(sha256Hex(dlBefore.body), 'the seeded wheel is builtA').toBe(builtA.sha256Hex);

      // Same filename, same declared version: a real override attempt, refused (fileAlreadyExists).
      const builtB = buildWheel({ name: layout.packageName, version, marker: 'v1-again' });
      expectMsgId(await rawUpload(layout.repoName, admin, builtB), 403, 'fileAlreadyExists');

      const dlAfterRefused = await rawDownload(
        layout.repoName,
        admin,
        layout.packageName,
        filename,
      );
      expect(
        sha256Hex(dlAfterRefused.body),
        'the refused override left the stored bytes alone',
      ).toBe(builtA.sha256Hex);

      // RPS-1223 (fixed): the SAME filename, but a form `version` that does not match the
      // version encoded in that filename. `isPackageFileExist` is now decided by the filename
      // alone, so this no longer bypasses the "already exists" check.
      const builtBypass = buildWheel({
        name: layout.packageName,
        version: `${version}.post9`,
        marker: 'bypass',
      });
      const bypassForm = new FormData();
      bypassForm.append(':action', 'file_upload');
      bypassForm.append('name', layout.packageName);
      bypassForm.append('version', `${version}.post9`);
      bypassForm.append('requires_python', builtBypass.requiresPython);
      bypassForm.append('sha256_digest', builtBypass.sha256Hex);
      // Reuse the ORIGINAL filename (declaring the ORIGINAL version), not the bypass wheel's own.
      bypassForm.append('content', new Blob([new Uint8Array(builtBypass.bytes)]), filename);
      const bypassRes = await fetch(uploadUrl(layout.repoName), {
        method: 'POST',
        headers: authHeader(admin),
        body: bypassForm,
      });
      const bypassBytes = Buffer.from(await bypassRes.arrayBuffer());

      expectMsgId({ status: bypassRes.status, body: bypassBytes }, 403, 'fileAlreadyExists');
    },
  );

  test(
    'a badVersionString form version is refused with 400, but ONLY after the archive and its ' +
      '.sha256 sidecar are already written to storage and downloadable (P4 / RPS-1124)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'badversion');
      const admin = adminCredential();
      const built = buildWheel({ name: layout.packageName, version: '1.2.3' });

      const form = new FormData();
      form.append(':action', 'file_upload');
      form.append('name', layout.packageName);
      form.append('version', 'not-a-version!!');
      form.append('requires_python', built.requiresPython);
      form.append('sha256_digest', built.sha256Hex);
      // The FILENAME must still be a valid archive filename (checkArchiveFilename runs first and is
      // independent of the form `version` field), so it carries a well-formed version of its own.
      form.append('content', new Blob([new Uint8Array(built.bytes)]), built.filename);
      const res = await fetch(uploadUrl(layout.repoName), {
        method: 'POST',
        headers: authHeader(admin),
        body: form,
      });
      const bytes = Buffer.from(await res.arrayBuffer());
      expectMsgId({ status: res.status, body: bytes }, 400, 'badVersionString');

      // P4 (RPS-1124 family, confirmed live): the orphaned file is downloadable directly even
      // though the upload was refused and no DB row/project-page entry exists for it.
      test.fail(
        true,
        'P4 (RPS-1124 family): writePackageArchive runs BEFORE ReleaseVersion.of(form.version) can ' +
          'throw badVersionString, so a validation failure after the storage write leaves an ' +
          'orphaned, downloadable archive with no DB row at all',
      );
      const dl = await rawDownload(layout.repoName, admin, layout.packageName, built.filename);
      expect(dl.status, 'the rejected upload left nothing downloadable').toBe(404);
    },
  );

  test(
    'a missing sha256_digest is refused with 400 sha256DigestMissing (RPS-1224)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'digestmissing');
      const admin = adminCredential();

      const missing = buildWheel({ name: layout.packageName, version: '1.0.0' });
      const missingForm = new FormData();
      missingForm.append(':action', 'file_upload');
      missingForm.append('name', missing.name);
      missingForm.append('version', missing.version);
      missingForm.append('requires_python', missing.requiresPython);
      // No sha256_digest field at all.
      missingForm.append('content', new Blob([new Uint8Array(missing.bytes)]), missing.filename);
      const missingRes = await fetch(uploadUrl(layout.repoName), {
        method: 'POST',
        headers: authHeader(admin),
        body: missingForm,
      });
      const missingBytes = Buffer.from(await missingRes.arrayBuffer());

      expectMsgId({ status: missingRes.status, body: missingBytes }, 400, 'sha256DigestMissing');
    },
  );

  test(
    'a wrong sha256_digest is refused with 400 sha256DigestMismatch, and a correct upload’s ' +
      'served hash is the server-computed digest of the actual bytes, not the client value (RPS-1225)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'digestwrong');
      const admin = adminCredential();

      // A digest that does not match the uploaded bytes is now rejected outright, not silently
      // stored and served back verbatim.
      const wrong = buildWheel({ name: layout.packageName, version: '1.0.0' });
      const wrongRes = await rawUpload(layout.repoName, admin, wrong, { sha256Digest: 'deadbeef' });
      expectMsgId(wrongRes, 400, 'sha256DigestMismatch');

      const wrongDl = await rawDownload(layout.repoName, admin, wrong.name, wrong.filename);
      expect(wrongDl.status, 'the rejected upload left nothing downloadable').toBe(404);

      // A correct upload is unaffected, and the served #sha256= is the server's own computation
      // over the actual bytes (proven separately from the client's own claim by sending it
      // uppercase here -- the server must normalize it, not echo it).
      const correct = buildWheel({ name: layout.packageName, version: '2.0.0' });
      const correctRes = await rawUpload(layout.repoName, admin, correct, {
        sha256Digest: correct.sha256Hex.toUpperCase(),
      });
      expectMsgId(correctRes, 200, undefined);

      const simpleRes = await rawGetSimplePage(layout.repoName, admin, correct.name);
      const links = parseSimplePage(simpleRes.body);
      const link = links.find((l) => l.filename === correct.filename);
      expect(link?.sha256, 'the served hash matches the real uploaded bytes, lowercased').toBe(
        correct.sha256Hex,
      );
    },
  );

  test(
    'the root /simple/ index links to the real, working project page, off the repo’s own ' +
      'URI (RPS-1221, fixed)',
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'rootindex');
      const admin = adminCredential();
      const built = buildWheel({
        name: layout.packageName,
        version: pypiAdapter.version('release'),
      });
      expectMsgId(await rawUpload(layout.repoName, admin, built), 200, undefined);

      const res = await rawGetSimpleRoot(layout.repoName, admin);
      expect(res.status, 'the root index is served').toBe(200);
      const links = parseSimpleRoot(res.body);
      const link = links.find((l) => l.text === layout.packageName);
      expect(link, `a root-index entry for "${layout.packageName}"`).toBeDefined();

      // RPS-1221 (fixed): the href no longer hard-codes a dead cloud-layout "/pypi/" prefix -- it
      // is now the request's own absolute repo URI plus one repo segment, matching the real
      // project page (and the per-project page's own href shape, H8).
      expect(link?.href, 'the root index links to the real, working project page').toBe(
        `${env.repoBaseUrl}/${layout.repoName}/simple/${layout.packageName}/`,
      );

      // The link must itself resolve, not just look plausible.
      const followed = await rawGetSimplePage(layout.repoName, admin, layout.packageName);
      expect(followed.status, 'following the rendered href resolves the real project page').toBe(
        200,
      );
    },
  );

  test(
    'GET of a non-normalized name and/or a missing trailing slash 307-redirects to the ' +
      'normalized project page (H14)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'redirect');
      const admin = adminCredential();
      const mixedCaseName = `E2E_${seeder.runId}_Redirect.Dots`;
      const normalizedName = 'e2e-' + `${seeder.runId}-redirect-dots`.toLowerCase();
      const built = buildWheel({ name: mixedCaseName, version: pypiAdapter.version('release') });
      expectMsgId(await rawUpload(layout.repoName, admin, built), 200, undefined);

      const noFollow = await rawGetSimplePageNoFollow(layout.repoName, admin, mixedCaseName);
      expect(noFollow.status, 'a non-normalized name redirects').toBe(307);
      expect(noFollow.location, 'redirects to the normalized, trailing-slashed project page').toBe(
        `${env.repoBaseUrl}/${layout.repoName}/simple/${normalizedName}/`,
      );

      const followed = await rawGetSimplePage(layout.repoName, admin, mixedCaseName);
      expect(followed.status, 'following the redirect lands on the real project page').toBe(200);
    },
  );

  test(
    'HEAD mirrors GET’s status: 404 for a path that was never published, 200 once it is ' +
      '(RPS-1226, fixed)',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const layout = await newRepo(seeder, 'head');
      const admin = adminCredential();
      const version = pypiAdapter.version('release');
      const built = buildWheel({ name: layout.packageName, version });

      // RPS-1226 (fixed): HEAD used to answer 200 unconditionally, existence never checked.
      const missingProject = await rawHead(layout.repoName, admin, simplePagePath('no-such-pkg'));
      expect(missingProject.status, 'HEAD of a project page that was never published').toBe(404);

      const missingFile = await rawHead(
        layout.repoName,
        admin,
        downloadPath(layout.packageName, wheelFilename(layout.packageName, version)),
      );
      expect(missingFile.status, 'HEAD of an archive file that was never published').toBe(404);

      expectMsgId(await rawUpload(layout.repoName, admin, built), 200, undefined);

      const existingProject = await rawHead(
        layout.repoName,
        admin,
        simplePagePath(layout.packageName),
      );
      expect(existingProject.status, 'HEAD of the now-published project page').toBe(200);

      const existingFile = await rawHead(
        layout.repoName,
        admin,
        downloadPath(layout.packageName, built.filename),
      );
      expect(existingFile.status, 'HEAD of the now-published archive file').toBe(200);

      const rootIndex = await rawHead(layout.repoName, admin, simpleRootPath());
      expect(rootIndex.status, 'HEAD of the root /simple/ index (the repo already resolved)').toBe(
        200,
      );
    },
  );

  test(
    'a pre-release/dev/post version publishes and is servable regardless of version kind: ' +
      'releases/snapshots are not pypi repo settings (the settings PUT refuses them, RPS-1210) (H23)',
    { tag: ['@settings'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
      await seeder.setSettings(repo.name, {
        privateRepo: true,
        allowOverride: true,
      });
      const admin = adminCredential();
      const name = `e2e-${seeder.runId}-norulesetting`;

      for (const version of ['1.0.0', '1.0.0a1', '1.0.0.post1', '1.0.0.dev0']) {
        const built = buildWheel({ name, version });
        expectMsgId(await rawUpload(repo.name, admin, built), 200, undefined);
        const dl = await rawDownload(repo.name, admin, name, built.filename);
        expect(dl.status, `"${version}" is servable`).toBe(200);
      }
    },
  );

  test('an unknown package/file 404s', { tag: ['@negative'] }, async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
    const admin = adminCredential();
    const name = `e2e-${seeder.runId}-unknown`;

    expectMsgId(await rawGetSimplePage(repo.name, admin, name), 404, 'packageNotFound');
    expectMsgId(
      await rawDownload(repo.name, admin, name, wheelFilename(name, '1.0.0')),
      404,
      'itemNotFound',
    );
  });

  test(
    'the packageName helper is already PEP 503-normalized, and the download URL uses one repo ' +
      'segment (H8, sanity)',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const name = rawPackageName(seeder.runId, {
        id: 'sanity-check',
        tags: [],
        repo: { privateRepo: true },
        credential: 'admin-password',
        expect: { publish: 'ok', consume: 'ok' },
      });
      expect(name).toBe(name.toLowerCase().replace(/[-_.]+/g, '-'));
      expect(downloadPath(name, wheelFilename(name, '1.0.0'))).toBe(
        `${name}/-/${wheelFilename(name, '1.0.0')}`,
      );
    },
  );
});
