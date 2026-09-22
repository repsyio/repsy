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
 * PyPI sdist tests (step 5h, RPS-294): publishing a SOURCE distribution (`.tar.gz`), as opposed to
 * the wheel `tests/pypi/publish-consume.spec.ts`'s catalog loop already covers. Ported from
 * `repsy-cloud`'s own e2e harness (`protocols/pypi/setup.template.py`'s `setup.py sdist bdist_wheel`
 * + `util.ts`'s `pypiUpload`/`pypiInstall`, which upload BOTH dist kinds together and never probe an
 * sdist-only install) into this harness's own conventions: real `twine`/`pip` binaries, a hand-built
 * `World`-less layout (a fresh private repo + `token-rw` deploy token per test, built by hand exactly
 * like `tests/cargo/protocol-specific.spec.ts`/`tests/nuget/protocol-specific.spec.ts`'s dedicated
 * tests) -- never that harness's own `npx tsx subprocess`/`shelljs`/`setup.py` structure. This suite
 * goes further than repsy-cloud's own coverage: it publishes an sdist-ONLY package (no wheel sibling
 * at all) and confirms live what a real `pip install`/`download` actually does when only a source
 * archive is available.
 *
 * `buildSdist` (`pypi-raw.ts`) hand-assembles the tarball the same way `buildWheel` hand-assembles a
 * wheel -- no `python -m build --sdist`/`setup.py sdist`/`setuptools` invocation anywhere (this
 * story's own constraint, and `runners/pypi.Dockerfile` does not even install `setuptools` -- see
 * that file's header). `PackageStorageUtils.checkArchiveFilename`'s upload grammar
 * (`repsy-protocols/pypi/.../utils/PackageStorageUtils.java`) already accepts `.tar.gz` alongside
 * `.whl`/`.zip`, and `AbstractPypiProtocolFacade.uploadPackage` never reads the form's
 * `filetype`/`pyversion` fields at all (both files read before writing this suite, cited in
 * `pypi-raw.ts`'s own header) -- so a wheel and an sdist take the IDENTICAL server-side code path;
 * the only question worth a dedicated live probe was the CLIENT side.
 *
 * PS-1/PS-2 were probed live against a running local stack BEFORE this file was written (see this
 * PR's own report for the exact commands/output):
 *
 *  - PS-1 (gating): does a real `twine upload` accept a hand-built sdist with only a `PKG-INFO` and
 *    no `setup.py`/`pyproject.toml` at all? Confirmed live -- `python3 -m twine upload` of exactly
 *    this shape (inside the built `pypi` runner container, against a running local stack) answered
 *    exit `0` on the FIRST attempt; twine's own metadata parser (`pkginfo.SDist`) reads `PKG-INFO`
 *    directly and needs neither file. The served project page then carried the correct
 *    `href`/`#sha256=`/`data-requires-python` -- confirmed live, `Requires-Python` in `PKG-INFO`
 *    round-trips through twine's form exactly like `buildWheel`'s own `METADATA` header does.
 *  - PS-2 (the decisive, un-obvious one -- H20 in README.md explicitly left this open): a plain
 *    `python3 -m pip download --no-deps --dest <dir> <name>==<version>` (deliberately WITHOUT
 *    `--only-binary=:all:`, the flag the catalog loop's own `resolve()` always passes) against a
 *    package that has ONLY an sdist. Confirmed live, running the REAL suite (this superseded an
 *    earlier, incomplete manual probe that used a hand-built sdist WITH a `setup.py`, predicting a
 *    build-isolation/`setuptools`-fetch failure instead -- see this PR's own report for both probes):
 *    pip finds and downloads the `.tar.gz` fine, then refuses to install it OUTRIGHT -- `ERROR:
 *    e2e-.../<file>.tar.gz#sha256=... does not appear to be a Python project: neither 'setup.py' nor
 *    'pyproject.toml' found`, exit `1` -- because `buildSdist` (`pypi-raw.ts`) deliberately never
 *    writes either file (this story's own "own the exact bytes, no setuptools" constraint, the sdist
 *    analogue of `buildWheel` never invoking `setuptools`/`wheel`). pip's own legacy-vs-PEP-517
 *    project-type detection runs BEFORE it would ever attempt build isolation, so this is an EARLIER,
 *    simpler and even more deterministic failure than a build-isolation dependency fetch would be --
 *    prompt (well under this suite's own timeout), no hang, no retry storm, a clean client-side
 *    refusal. Not a Repsy bug: the server already served the archive correctly (PS-1's own
 *    project-page/download assertions prove that); every OTHER real client in this harness ships its
 *    own toolchain deliberately (`helm.Dockerfile`'s Helm binary, `golang.Dockerfile`'s Go toolchain,
 *    `ruby.Dockerfile`'s RubyGems) and pip's PEP 517 build backend was never part of
 *    `pypi.Dockerfile`'s own toolchain either -- this is simply what a real `pip` does against a
 *    source-only, no-`setup.py` archive, live-confirmed rather than assumed, matching H20's own "if
 *    time-boxed" scope note that this exact question was left open.
 *
 * No NEW backend bug was found while building this suite -- both hypotheses matched what reading
 * `PackageStorageUtils`/`AbstractPypiProtocolFacade` predicted (a wheel and an sdist share the exact
 * same upload/download/project-page code, so nothing server-side needed live probing beyond
 * confirming a real `twine upload` actually sends what `checkArchiveFilename`'s grammar expects).
 * PS-2's exact FAILURE SHAPE was the one detail that needed the REAL suite run to pin correctly
 * (this file's header above): an earlier manual probe with a `setup.py` included predicted a
 * different, later failure (build isolation failing to fetch `setuptools`), which does not apply to
 * `buildSdist`'s own no-`setup.py` shape -- corrected before this file was finalized, not left
 * inconsistent with what the suite actually asserts.
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { pipEnv, pypiAdapter, twineEnv } from '../../src/clients/pypi.js';
import {
  adminCredential,
  type BuiltSdist,
  buildSdist,
  parseSimplePage,
  rawDownload,
  rawGetSimplePage,
  sha256Hex,
  uploadUrl,
} from '../../src/clients/pypi-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { MaterializedCredential } from '../../src/scenarios/world.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface Layout {
  repoName: string;
  packageName: string;
  credential: MaterializedCredential;
}

/** A fresh private pypi repo, a run-unique package name, and a fresh `token-rw` deploy token for
 *  it -- the credential every hand-built test in this file uses, exactly like
 *  `tests/cargo/protocol-specific.spec.ts`/`tests/nuget/protocol-specific.spec.ts`'s own
 *  `newRepoWithToken`. `packageName` is already PEP 503-normalized (`seeder.runId` is
 *  lowercase-alnum, `label` below is always a lowercase-hyphen literal), matching `pypi-raw.ts`'s
 *  own `packageName()` convention so the server never 307-redirects this suite's own requests. */
async function newRepoWithToken(seeder: Seeder, label: string): Promise<Layout> {
  const repo = await seeder.createRepo(RepoType.PYPI, { privateRepo: true });
  const token = await seeder.createToken(repo.name, { readOnly: false });
  const credential: MaterializedCredential = {
    transport: 'basic',
    username: token.username,
    password: token.token,
    kind: 'token',
  };
  return { repoName: repo.name, packageName: `e2e-${seeder.runId}-${label}`, credential };
}

/** Runs a real `python3 -m twine upload` of a hand-built sdist (`buildSdist`), mirroring
 *  `pypi.ts`'s own `publishWithClient` -- the sdist analogue, never invoked from `pypi.ts` itself
 *  (that file's `publish()`/`seedPublish()` are wheel-only, unchanged by this suite). */
async function publishSdist(
  layout: Layout,
  version: string,
  label: string,
): Promise<{ exitCode: number; command: string; built: BuiltSdist }> {
  const { home, work } = await isolatedWorkDir(label);
  const built = buildSdist({ name: layout.packageName, version, marker: randomUUID() });

  const distDir = path.join(work, 'dist');
  await fs.mkdir(distDir, { recursive: true });
  const sdistFile = path.join(distDir, built.filename);
  await fs.writeFile(sdistFile, built.bytes);

  const secrets = layout.credential.password ? [layout.credential.password] : [];
  const execResult = await run(
    'python3',
    [
      '-m',
      'twine',
      'upload',
      '--non-interactive',
      '--disable-progress-bar',
      '--repository-url',
      uploadUrl(layout.repoName),
      sdistFile,
    ],
    {
      cwd: work,
      env: twineEnv(home, layout.credential),
      timeoutMs: 120_000,
      redact: secrets,
      label,
    },
  );

  return { exitCode: execResult.exitCode, command: execResult.command, built };
}

test(
  'pypi > a real twine upload of a hand-built sdist publishes, lists and downloads correctly',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'sdist-pub');
    const version = pypiAdapter.version('release');

    const published = await publishSdist(layout, version, `pypi-sdist-pub-${seeder.runId}`);
    expect(
      published.exitCode,
      `twine upload of a hand-built sdist (no setup.py/pyproject.toml): ${published.command}`,
    ).toBe(0);

    const admin = adminCredential();
    const pageRes = await rawGetSimplePage(layout.repoName, admin, layout.packageName);
    expect(pageRes.status, 'the project page exists for the published sdist').toBe(200);

    const links = parseSimplePage(pageRes.body);
    const link = links.find((l) => l.filename === published.built.filename);
    expect(link, `a project-page entry for "${published.built.filename}"`).toBeDefined();
    expect(link?.sha256, 'the index fragment matches the published sdist bytes').toBe(
      published.built.sha256Hex,
    );
    expect(
      link?.requiresPython,
      'Requires-Python (from PKG-INFO, via twine) round-trips through the HTML escape/unescape ' +
        'exactly like a wheel’s METADATA header does (PS-1)',
    ).toBe(published.built.requiresPython);

    const dlRes = await rawDownload(
      layout.repoName,
      admin,
      layout.packageName,
      published.built.filename,
    );
    expect(dlRes.status, 'the advertised download URL resolves').toBe(200);
    expect(sha256Hex(dlRes.body), 'the served sdist matches the published bytes verbatim').toBe(
      published.built.sha256Hex,
    );
  },
);

test(
  'pypi > pip cannot resolve a source-only package on this runner image -- live-confirmed, not a ' +
    'Repsy bug (PS-2, README.md’s H20)',
  { tag: ['@smoke', '@negative'] },
  async ({ seeder }) => {
    const layout = await newRepoWithToken(seeder, 'sdist-only');
    const version = pypiAdapter.version('release');

    const published = await publishSdist(layout, version, `pypi-sdist-only-pub-${seeder.runId}`);
    expect(published.exitCode, `twine upload: ${published.command}`).toBe(0);

    // Deliberately NOT --only-binary=:all: (the flag `pypi.ts`'s own resolve() always passes for
    // the catalog loop) -- this is exactly the "let pip try to build it" path PS-2 probes.
    const { home, work } = await isolatedWorkDir(`pypi-sdist-only-con-${seeder.runId}`);
    const destDir = path.join(work, 'downloads');
    await fs.mkdir(destDir, { recursive: true });

    const execResult = await run(
      'python3',
      [
        '-m',
        'pip',
        'download',
        '--no-deps',
        '--no-cache-dir',
        '--dest',
        destDir,
        `${layout.packageName}==${version}`,
      ],
      {
        cwd: work,
        env: pipEnv(home, layout.credential, layout.repoName),
        timeoutMs: 120_000,
        redact: layout.credential.password ? [layout.credential.password] : [],
        label: `pypi-sdist-only-consume-${seeder.runId}`,
      },
    );

    // Confirmed live (PS-2, this file's own header -- this exact message only surfaced running the
    // REAL suite, not the manual probe that predicted a build-isolation/setuptools failure instead,
    // see this PR's own report): pip downloads the sdist fine, then refuses to install it OUTRIGHT
    // -- "does not appear to be a Python project: neither 'setup.py' nor 'pyproject.toml' found" --
    // because `buildSdist` deliberately never writes either file (this story's own "own the exact
    // bytes, no setuptools" constraint, `pypi-raw.ts`'s file header). pip's own legacy-vs-PEP-517
    // project detection runs BEFORE it ever attempts build isolation, so this is an even earlier,
    // simpler and more deterministic failure than a build-isolation dependency fetch would be -- a
    // clean, prompt, client-side refusal, not a hang, not a crash, not a Repsy-side bug (the
    // previous test's own publish/list/download assertions already prove the archive itself is
    // served correctly).
    expect(
      execResult.exitCode,
      `a source-only "pip download" without --only-binary fails client-side on this runner image ` +
        `(the hand-built sdist carries neither setup.py nor pyproject.toml): ${execResult.command}`,
    ).not.toBe(0);
    expect(
      execResult.stdout + execResult.stderr,
      'the failure is specifically pip refusing to treat this as an installable Python project at ' +
        `all, not some other unrelated failure. Full output:\n${execResult.stdout}\n${execResult.stderr}`,
    ).toContain(
      "does not appear to be a Python project: neither 'setup.py' nor 'pyproject.toml' found",
    );

    const nothingDownloaded = await fs.readdir(destDir);
    expect(
      nothingDownloaded,
      'a failed build leaves no installable artifact under --dest (pip cleans up its own download ' +
        'once the build step it gates on fails)',
    ).toHaveLength(0);
  },
);
