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
 * Helm OCI provenance (`.prov` signing, RPS-1719 batch C7): `helm package --sign` produces a
 * detached PGP signature file, and a real `helm push` uploads it alongside the chart with no extra
 * flag ("If the chart has an associated provenance file, it will also be uploaded.", `helm push
 * --help`, confirmed live). The CLASSIC (ChartMuseum-protocol) `.prov` route is explicitly OUT of
 * scope here -- no route exists yet server-side, a genuine open product decision the ticket
 * already flags -- this file is OCI only.
 *
 * **Probed live before writing this file** (RPS-1719's own gap analysis flagged
 * `AbstractHelmOciManifestPushProtocolMethodHandler.parseChartLayer` as reading only `layers[0]`
 * and called `--verify` "likely broken today"): a signed push against a real local Repsy actually
 * round-trips cleanly. `parseChartLayer` reads `layers[0]` only to identify the CHART layer for its
 * own bookkeeping (name/version/digest/size); it neither rejects nor special-cases a second layer.
 * The `.prov` blob is stored and served by the SAME generic by-digest blob routes every other layer
 * uses, and the manifest GET returns the raw JSON verbatim, so the second layer (media type
 * `application/vnd.cncf.helm.chart.provenance.v1.prov`, `HELM_MEDIA_TYPES.prov`) survives untouched.
 * Confirmed with the real `helm` v4.3.0 binary against a live local stack: signed push, a fetch of
 * the `.prov` blob by digest byte-identical to the local file, and a real `helm pull --verify`
 * against a keyring holding the signer's public key all succeed.
 *
 * **One client-side finding, not a Repsy defect**: Helm's own `pull --help` says a `--verify`
 * failure means "the chart will not be saved locally" -- confirmed live that this is NOT true of
 * Helm v4.3.0: a `pull --verify` against a keyring that does not hold the signer's key exits
 * non-zero with a clear `openpgp: signature made by unknown entity` error (verification itself is
 * genuinely enforced, not a no-op), but still writes the unverified `.tgz`/`.tgz.prov` to the
 * destination directory. "R-prov-3" below asserts the exit code and the error, not file absence.
 *
 * Real gpg key generation/export (`src/clients/gpg.ts`'s `generateGpgKey`/`exportSecretKeyArmored`,
 * shared with the maven runner's signed-deploy specs, RPS-1316) needs the `gpg` binary on the helm
 * runner's PATH, added to `runners/helm.Dockerfile` for this story -- `helm package --sign` itself
 * never shells out to `gpg`, it reads the exported armored keyring as a plain file (confirmed live).
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { exportSecretKeyArmored, generateGpgKey, type GpgKey } from '../../src/clients/gpg.js';
import { writeChartDir } from '../../src/clients/helm-chart.js';
import {
  helmAdapter,
  helmEnv,
  plainHttpFlag,
  renderHelmRegistryConfig,
} from '../../src/clients/helm.js';
import {
  adminCredential,
  chartFileName,
  HELM_MEDIA_TYPES,
  ociChartRef,
  ociRepoRef,
  rawGetBlob,
  rawGetManifest,
  sha256Hex,
} from '../../src/clients/helm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import type { Seeder } from '../../src/seed/seeder.js';

interface SignedChart {
  tgzPath: string;
  provPath: string;
}

/**
 * `helm package --sign` a chart directory with a real GPG key exported to a keyring FILE
 * (`exportSecretKeyArmored`): Helm's own PGP implementation parses that file directly and never
 * shells out to `gpg` to sign (confirmed live, this file's header). Produces
 * `<name>-<version>.tgz` and its detached `.tgz.prov` next to it in `work`.
 */
async function packageSignedChart(
  home: string,
  work: string,
  opts: { name: string; version: string },
  signing: { uid: string; secretKeyringPath: string; passphrasePath: string },
): Promise<SignedChart> {
  const chartDir = await writeChartDir(work, opts);
  const packageResult = await run(
    'helm',
    [
      'package',
      '--sign',
      '--key',
      signing.uid,
      '--keyring',
      signing.secretKeyringPath,
      '--passphrase-file',
      signing.passphrasePath,
      chartDir,
      '--destination',
      work,
    ],
    { cwd: work, env: helmEnv(home), timeoutMs: 60_000, label: 'helm-package-sign' },
  );
  expect(packageResult.exitCode, `helm package --sign: ${packageResult.command}`).toBe(0);

  return {
    tgzPath: path.join(work, `${opts.name}-${opts.version}.tgz`),
    provPath: path.join(work, `${opts.name}-${opts.version}.tgz.prov`),
  };
}

interface SignedPublish {
  repoName: string;
  chart: string;
  version: string;
  key: GpgKey;
  signed: SignedChart;
}

/** A fresh private repo, a fresh real GPG key, a signed chart and a real `helm push` of it
 *  (including its `.prov`) -- the shared setup of every test below. */
async function publishSignedChart(seeder: Seeder, label: string): Promise<SignedPublish> {
  const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
  const credential = adminCredential();
  const chart = `e2e-${seeder.runId}-${label}`;
  const version = helmAdapter.version('release');
  const key = await generateGpgKey();

  const { home, work } = await isolatedWorkDir(`helm-${label}-pub-${seeder.runId}`);
  const secretKeyringPath = path.join(work, 'signing-secret.asc');
  await fs.writeFile(secretKeyringPath, await exportSecretKeyArmored(key, work), 'utf8');
  const passphrasePath = path.join(work, 'signing-passphrase.txt');
  await fs.writeFile(passphrasePath, `${key.passphrase}\n`, { mode: 0o600 });

  const signed = await packageSignedChart(
    home,
    work,
    { name: chart, version },
    { uid: key.uid, secretKeyringPath, passphrasePath },
  );
  expect((await fs.stat(signed.tgzPath)).isFile(), 'helm package wrote the chart .tgz').toBe(true);
  expect(
    (await fs.stat(signed.provPath)).isFile(),
    'helm package --sign wrote the .prov file',
  ).toBe(true);

  await renderHelmRegistryConfig(home, credential);
  const pushResult = await run(
    'helm',
    ['push', signed.tgzPath, ociRepoRef(repo.name), ...plainHttpFlag()],
    { cwd: work, env: helmEnv(home), timeoutMs: 60_000, label: `helm-${label}-push` },
  );
  // No extra flag: a real `helm push` uploads a `.prov` sitting next to the `.tgz` on its own
  // (`helm push --help`, confirmed live -- this file's header).
  expect(pushResult.exitCode, `helm push (with .prov): ${pushResult.command}`).toBe(0);

  return { repoName: repo.name, chart, version, key, signed };
}

test.describe('helm > OCI provenance (.prov signing, RPS-1719)', () => {
  test(
    'R-prov-1: a signed "helm push" round-trips the .prov as a second manifest layer, and ' +
      '"helm pull --verify" against the matching keyring succeeds',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const credential = adminCredential();
      const published = await publishSignedChart(seeder, 'prov1');
      try {
        // The manifest genuinely carries a SECOND layer for the prov (not just a successful exit
        // code): AbstractHelmOciManifestPushProtocolMethodHandler.parseChartLayer only reads
        // layers[0] for its own chart bookkeeping (RPS-1719's own gap analysis flagged this as a
        // possible gap) -- confirm here it neither rejects nor drops the second layer.
        const manifestRes = await rawGetManifest(
          published.repoName,
          credential,
          published.chart,
          published.version,
        );
        expect(manifestRes.status, 'GET manifest after a signed push').toBe(200);
        const manifest = JSON.parse(manifestRes.body.toString('utf8')) as {
          layers?: { mediaType?: string; digest?: string; size?: number }[];
        };
        expect(manifest.layers?.length, 'manifest should carry chart + prov layers').toBe(2);
        const provLayer = manifest.layers?.find((l) => l.mediaType === HELM_MEDIA_TYPES.prov);
        expect(provLayer, 'manifest should have a provenance layer').toBeDefined();

        const provBytes = await fs.readFile(published.signed.provPath);
        const provDigest = `sha256:${sha256Hex(provBytes)}`;
        expect(provLayer?.digest, "the manifest's prov layer digest matches the local .prov").toBe(
          provDigest,
        );

        // The prov blob is retrievable directly by digest -- the same generic by-digest blob GET
        // every other layer uses -- and byte-identical to what was pushed, not merely "helm says
        // pull succeeded".
        const blobRes = await rawGetBlob(published.repoName, credential, published.chart, provDigest);
        expect(blobRes.status, 'GET the prov blob by digest').toBe(200);
        expect(sha256Hex(blobRes.body), 'the prov blob round-trips byte for byte').toBe(
          sha256Hex(provBytes),
        );

        // Real client-side verification: a SEPARATE consumer, its own isolated home and its own
        // registry login (each isolated home needs its own login -- the bug C6 had to fix), with a
        // keyring holding ONLY the signer's public key.
        const { home: conHome, work: conWork } = await isolatedWorkDir(
          `helm-prov1-con-${seeder.runId}`,
        );
        await renderHelmRegistryConfig(conHome, credential);
        const pubKeyringPath = path.join(conWork, 'signer-pub.asc');
        await fs.writeFile(pubKeyringPath, published.key.publicKeyArmored, 'utf8');
        const pulledDir = path.join(conWork, 'pulled');
        await fs.mkdir(pulledDir, { recursive: true });

        const pullVerifyResult = await run(
          'helm',
          [
            'pull',
            ociChartRef(published.repoName, published.chart),
            '--version',
            published.version,
            '--verify',
            '--keyring',
            pubKeyringPath,
            '--destination',
            pulledDir,
            ...plainHttpFlag(),
          ],
          { cwd: conWork, env: helmEnv(conHome), timeoutMs: 60_000, label: 'helm-prov1-pull-verify' },
        );
        expect(pullVerifyResult.exitCode, `helm pull --verify: ${pullVerifyResult.command}`).toBe(0);
        expect(pullVerifyResult.stdout, 'pull --verify reports who signed it').toMatch(/Signed by/i);

        const pulledChart = path.join(pulledDir, chartFileName(published.chart, published.version));
        const pulledProv = `${pulledChart}.prov`;
        expect((await fs.stat(pulledChart)).isFile(), 'verified chart saved').toBe(true);
        expect((await fs.stat(pulledProv)).isFile(), 'verified .prov saved').toBe(true);
      } finally {
        await published.key.dispose();
      }
    },
  );

  test(
    'R-prov-2: tampering with either the round-tripped chart bytes or its signature breaks ' +
      '"helm verify" (flip-and-fail against a real signed control)',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const credential = adminCredential();
      const published = await publishSignedChart(seeder, 'prov2');
      try {
        // A clean consumer pull first: `--prov` fetches the signature without verifying, so what
        // follows tampers with content that genuinely round-tripped through Repsy, not the
        // publisher's own locally-built files.
        const { home: conHome, work: conWork } = await isolatedWorkDir(
          `helm-prov2-con-${seeder.runId}`,
        );
        await renderHelmRegistryConfig(conHome, credential);
        const pubKeyringPath = path.join(conWork, 'signer-pub.asc');
        await fs.writeFile(pubKeyringPath, published.key.publicKeyArmored, 'utf8');
        const pulledDir = path.join(conWork, 'pulled');
        await fs.mkdir(pulledDir, { recursive: true });

        const pullResult = await run(
          'helm',
          [
            'pull',
            ociChartRef(published.repoName, published.chart),
            '--version',
            published.version,
            '--prov',
            '--destination',
            pulledDir,
            ...plainHttpFlag(),
          ],
          { cwd: conWork, env: helmEnv(conHome), timeoutMs: 60_000, label: 'helm-prov2-pull' },
        );
        expect(pullResult.exitCode, `helm pull --prov: ${pullResult.command}`).toBe(0);

        const chartName = chartFileName(published.chart, published.version);
        const pulledChart = path.join(pulledDir, chartName);
        const pulledProv = `${pulledChart}.prov`;
        const originalChartBytes = await fs.readFile(pulledChart);
        const originalProvBytes = await fs.readFile(pulledProv);

        // Control: the untampered, round-tripped pair verifies -- proves the two failures below
        // are real, not an environment that always rejects "verify".
        const controlVerify = await run('helm', ['verify', pulledChart, '--keyring', pubKeyringPath], {
          cwd: conWork,
          env: helmEnv(conHome),
          timeoutMs: 30_000,
          label: 'helm-prov2-verify-control',
        });
        expect(controlVerify.exitCode, `helm verify (untampered control): ${controlVerify.command}`).toBe(
          0,
        );

        // Tamper the CHART bytes alone: the .prov's own recorded sha256 no longer matches.
        await fs.writeFile(pulledChart, Buffer.concat([originalChartBytes, Buffer.from([0x58])]));
        const chartTamperVerify = await run(
          'helm',
          ['verify', pulledChart, '--keyring', pubKeyringPath],
          { cwd: conWork, env: helmEnv(conHome), timeoutMs: 30_000, label: 'helm-prov2-verify-chart' },
        );
        expect(chartTamperVerify.exitCode, 'verify must FAIL on a tampered chart').not.toBe(0);
        expect(chartTamperVerify.stderr + chartTamperVerify.stdout).toMatch(
          /does not match|sha256/i,
        );

        // Restore the chart, tamper the SIGNATURE instead: flip one bit inside the armored body.
        await fs.writeFile(pulledChart, originalChartBytes);
        const tamperedProv = Buffer.from(originalProvBytes);
        tamperedProv[Math.floor(tamperedProv.length / 2)] ^= 0xff;
        await fs.writeFile(pulledProv, tamperedProv);
        const provTamperVerify = await run(
          'helm',
          ['verify', pulledChart, '--keyring', pubKeyringPath],
          { cwd: conWork, env: helmEnv(conHome), timeoutMs: 30_000, label: 'helm-prov2-verify-prov' },
        );
        expect(provTamperVerify.exitCode, 'verify must FAIL on a tampered signature').not.toBe(0);
        expect(provTamperVerify.stderr + provTamperVerify.stdout, 'a real error is reported').toMatch(
          /error/i,
        );
      } finally {
        await published.key.dispose();
      }
    },
  );

  test(
    'R-prov-3: "helm pull --verify" against a keyring that does not hold the signer\'s key ' +
      'rejects the chart end to end, with a genuine signature error',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const credential = adminCredential();
      const published = await publishSignedChart(seeder, 'prov3');
      const wrongKey = await generateGpgKey();
      try {
        const { home: conHome, work: conWork } = await isolatedWorkDir(
          `helm-prov3-con-${seeder.runId}`,
        );
        await renderHelmRegistryConfig(conHome, credential);
        const wrongKeyringPath = path.join(conWork, 'unrelated-pub.asc');
        await fs.writeFile(wrongKeyringPath, wrongKey.publicKeyArmored, 'utf8');
        const pulledDir = path.join(conWork, 'pulled');
        await fs.mkdir(pulledDir, { recursive: true });

        const pullVerifyResult = await run(
          'helm',
          [
            'pull',
            ociChartRef(published.repoName, published.chart),
            '--version',
            published.version,
            '--verify',
            '--keyring',
            wrongKeyringPath,
            '--destination',
            pulledDir,
            ...plainHttpFlag(),
          ],
          {
            cwd: conWork,
            env: helmEnv(conHome),
            timeoutMs: 60_000,
            label: 'helm-prov3-pull-verify-wrong-key',
          },
        );
        // Confirmed live (this file's header): verification is genuinely enforced -- a keyring
        // without the signer's key fails with a clear signature error, not a silent pass.
        expect(
          pullVerifyResult.exitCode,
          "pull --verify must FAIL against a keyring that does not hold the signer's key",
        ).not.toBe(0);
        expect(pullVerifyResult.stderr + pullVerifyResult.stdout).toMatch(
          /unknown entity|signature|openpgp/i,
        );
        // NOT asserted: that the chart file is absent from the destination. Helm's own --help says
        // a --verify failure means "the chart will not be saved locally", but confirmed live this
        // does not hold for Helm v4.3.0 -- the unverified files ARE written despite the non-zero
        // exit and the error. That is a Helm client-side quirk, not something Repsy controls.
      } finally {
        await published.key.dispose();
        await wrongKey.dispose();
      }
    },
  );
});
