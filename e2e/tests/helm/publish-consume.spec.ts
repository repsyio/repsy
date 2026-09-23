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
 * The scenario-driven Helm OCI suite (step 4b, RPS-294): `registerPublishConsumeLoop(helmAdapter)`
 * wires the whole shared catalog into Helm's OCI distribution-spec protocol, exactly like
 * `tests/docker/publish-consume.spec.ts` does for Docker. Plus real-client tests the catalog loop
 * itself cannot exercise, numbered "HL" per the plan:
 *
 *  - "HL1" `helm registry login --password-stdin` writes the exact `config.json` shape
 *    `renderHelmRegistryConfig` renders by hand, fronts a working `helm push`; and documents
 *    **B-H4** (candidate): the SAME login with a WRONG password also reports success (confirmed
 *    live -- the ping's token endpoint is Docker's own, and issues an anonymous token before any
 *    credential is checked, see `helm-raw.ts`'s file header) -- but the push it then fronts still
 *    fails, so this adapter never trusts `helm registry login`'s own exit code for an `Outcome`.
 *  - "HL2" `helm pull oci://.../<chart>` with NO `--version` succeeds, resolving the latest
 *    version via `GET .../tags/list` (**B-H3**, fixed by RPS-1219); the same pull WITH an exact
 *    `--version` also succeeds (control).
 *  - "HL4" cross-mode (**B-H1**, fixed by RPS-1217): an OCI-pushed chart's version appears in
 *    `index.yaml`, and `helm pull --repo <url> <chart> --version <v>` (the classic route)
 *    downloads it too, via the classic-to-OCI-blob fallback.
 *  - "HL5" (**B-H2**, fixed by RPS-1218): an accepted OCI override with different chart bytes
 *    updates `index.yaml`'s `digest` field to the NEW layer digest.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { buildChart, writeChartFile } from '../../src/clients/helm-chart.js';
import {
  helmAdapter,
  helmEnv,
  plainHttpFlag,
  renderHelmRegistryConfig,
} from '../../src/clients/helm.js';
import {
  adminCredential,
  chartFileName,
  indexEntry,
  ociChartRef,
  ociRepoRef,
  parseIndex,
  rawGetIndex,
} from '../../src/clients/helm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';

registerPublishConsumeLoop(helmAdapter);

test(
  'helm > HL1: helm registry login --password-stdin writes the same config.json shape as ' +
    'renderHelmRegistryConfig, and fronts a working push; a wrong password also reports success ' +
    '(candidate B-H4) but the push it fronts still fails',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const chart = `e2e-${seeder.runId}-hl1`;
    const version = helmAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`helm-hl1-${seeder.runId}`);
    const built = await buildChart({ name: chart, version, marker: 'hl1' });
    const tgzFile = await writeChartFile(work, built);

    const loginResult = await run(
      'helm',
      [
        'registry',
        'login',
        'localhost:9090',
        '-u',
        token.username,
        '--password-stdin',
        ...plainHttpFlag(),
      ],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 30_000,
        redact: [token.token],
        label: 'helm-hl1-login',
        input: `${token.token}\n`,
      },
    );
    expect(loginResult.exitCode, `helm registry login: ${loginResult.command}`).toBe(0);

    const written = JSON.parse(
      await fs.readFile(path.join(home, 'config', 'registry', 'config.json'), 'utf8'),
    ) as { auths: Record<string, { auth: string }> };
    const expectedAuth = Buffer.from(`${token.username}:${token.token}`).toString('base64');
    expect(
      written.auths['localhost:9090']?.auth,
      'the same shape renderHelmRegistryConfig writes',
    ).toBe(expectedAuth);

    const pushResult = await run(
      'helm',
      ['push', tgzFile, ociRepoRef(repo.name), ...plainHttpFlag()],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        label: 'helm-hl1-push',
      },
    );
    expect(pushResult.exitCode, `helm push fronted by a correct login: ${pushResult.command}`).toBe(
      0,
    );

    // Candidate B-H4, confirmed live: a login with a WRONG password still reports success (the
    // ping's token endpoint is Docker's own and issues an anonymous token before any credential is
    // checked -- see helm-raw.ts's file header). Documented as an expected failure here, not
    // silently accepted: if this ever starts genuinely refusing a wrong password, this test should
    // start failing so it gets noticed.
    const { home: badHome, work: badWork } = await isolatedWorkDir(`helm-hl1-bad-${seeder.runId}`);
    const badLoginResult = await run(
      'helm',
      [
        'registry',
        'login',
        'localhost:9090',
        '-u',
        token.username,
        '--password-stdin',
        ...plainHttpFlag(),
      ],
      {
        cwd: badWork,
        env: helmEnv(badHome),
        timeoutMs: 30_000,
        label: 'helm-hl1-bad-login',
        input: 'not-the-real-token\n',
      },
    );
    test.fail(
      true,
      'RPS-1220 (B-H4): helm registry login reports success even with a WRONG password -- the ' +
        "ping's token endpoint is Docker's own and issues an anonymous token before any credential " +
        "is checked (helm-raw.ts). This is a Docker-provider bug surfacing through Helm's login flow.",
    );
    expect(badLoginResult.exitCode, 'a login with a wrong password should fail').not.toBe(0);

    // The push it fronts still fails for real (the actual write request IS credential-checked).
    const badPushResult = await run(
      'helm',
      ['push', tgzFile, ociRepoRef(repo.name), ...plainHttpFlag()],
      { cwd: badWork, env: helmEnv(badHome), timeoutMs: 60_000, label: 'helm-hl1-bad-push' },
    );
    expect(badPushResult.exitCode, 'a push fronted by a wrong password still fails').not.toBe(0);
  },
);

test(
  'helm > HL2: helm pull without --version succeeds (via tags/list); the same pull with an ' +
    'exact --version also succeeds',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const credential = adminCredential();
    const chart = `e2e-${seeder.runId}-hl2`;
    const version = helmAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`helm-hl2-${seeder.runId}`);
    const built = await buildChart({ name: chart, version, marker: 'hl2' });
    const tgzFile = await writeChartFile(work, built);
    await renderHelmRegistryConfig(home, credential);

    const pushResult = await run(
      'helm',
      ['push', tgzFile, ociRepoRef(repo.name), ...plainHttpFlag()],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        label: 'helm-hl2-push',
      },
    );
    expect(pushResult.exitCode).toBe(0);

    // helm pull does not create --destination itself (see helm.ts's resolve()).
    const noVersionDir = path.join(work, 'pulled-noversion');
    await fs.mkdir(noVersionDir, { recursive: true });
    const noVersionResult = await run(
      'helm',
      ['pull', ociChartRef(repo.name, chart), '--destination', noVersionDir, ...plainHttpFlag()],
      { cwd: work, env: helmEnv(home), timeoutMs: 30_000, label: 'helm-hl2-pull-noversion' },
    );
    expect(noVersionResult.exitCode, 'pull without --version should succeed').toBe(0);

    const exactDir = path.join(work, 'pulled-exact');
    await fs.mkdir(exactDir, { recursive: true });
    const exactResult = await run(
      'helm',
      [
        'pull',
        ociChartRef(repo.name, chart),
        '--version',
        version,
        '--destination',
        exactDir,
        ...plainHttpFlag(),
      ],
      { cwd: work, env: helmEnv(home), timeoutMs: 30_000, label: 'helm-hl2-pull-exact' },
    );
    expect(exactResult.exitCode, 'pull WITH an exact --version (control) succeeds').toBe(0);
  },
);

test(
  'helm > HL4: an OCI-pushed chart appears in index.yaml and is downloadable through the classic ' +
    'route',
  { tag: ['@settings'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const credential = adminCredential();
    const chart = `e2e-${seeder.runId}-hl4`;
    const version = helmAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`helm-hl4-${seeder.runId}`);
    const built = await buildChart({ name: chart, version, marker: 'hl4' });
    const tgzFile = await writeChartFile(work, built);
    await renderHelmRegistryConfig(home, credential);

    const pushResult = await run(
      'helm',
      ['push', tgzFile, ociRepoRef(repo.name), ...plainHttpFlag()],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        label: 'helm-hl4-push',
      },
    );
    expect(pushResult.exitCode).toBe(0);

    const indexRes = await rawGetIndex(repo.name, credential);
    expect(indexRes.status).toBe(200);
    const entry = indexEntry(parseIndex(indexRes.body.toString('utf8')), chart, version);
    expect(entry, 'index.yaml lists the OCI-pushed version').toBeDefined();
    expect(entry?.urls[0]).toBe(`charts/${chartFileName(chart, version)}`);

    const pulledClassicDir = path.join(work, 'pulled-classic');
    await fs.mkdir(pulledClassicDir, { recursive: true });
    const pullResult = await run(
      'helm',
      [
        'pull',
        '--repo',
        `http://localhost:9090/${repo.name}`,
        chart,
        '--version',
        version,
        '--destination',
        pulledClassicDir,
        // The repo is privateRepo: true; a classic `helm pull --repo` sends no credentials of its
        // own (unlike helm-classic.ts's adapter, which always appends these for the same reason),
        // so index.yaml's own fetch would 401 without them.
        '--username',
        credential.username ?? '',
        '--password',
        credential.password ?? '',
      ],
      { cwd: work, env: helmEnv(home), timeoutMs: 30_000, label: 'helm-hl4-pull-classic' },
    );
    expect(pullResult.exitCode, 'a classic pull of an OCI-only chart should succeed').toBe(0);
  },
);

test(
  "helm > HL5: an accepted OCI override with different bytes updates index.yaml's digest",
  { tag: ['@settings'] },
  async ({ seeder }) => {
    // A freshly created repo's own default is allowOverride: true.
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const credential = adminCredential();
    const chart = `e2e-${seeder.runId}-hl5`;
    const version = helmAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`helm-hl5-${seeder.runId}`);
    await renderHelmRegistryConfig(home, credential);

    const first = await buildChart({ name: chart, version, marker: 'hl5-v1' });
    const firstFile = await writeChartFile(path.join(work, 'v1'), first);
    const firstPush = await run(
      'helm',
      ['push', firstFile, ociRepoRef(repo.name), ...plainHttpFlag()],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        label: 'helm-hl5-push-v1',
      },
    );
    expect(firstPush.exitCode).toBe(0);

    const second = await buildChart({ name: chart, version, marker: 'hl5-v2-different' });
    const secondFile = await writeChartFile(path.join(work, 'v2'), second);
    const secondPush = await run(
      'helm',
      ['push', secondFile, ociRepoRef(repo.name), ...plainHttpFlag()],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        label: 'helm-hl5-push-v2',
      },
    );
    expect(secondPush.exitCode, 'allowOverride defaults to true').toBe(0);
    expect(first.tgzDigest).not.toBe(second.tgzDigest);

    const indexRes = await rawGetIndex(repo.name, credential);
    const entry = indexEntry(parseIndex(indexRes.body.toString('utf8')), chart, version);
    expect(entry?.digest, "index.yaml's digest should follow the override").toBe(second.tgzDigest);
  },
);
