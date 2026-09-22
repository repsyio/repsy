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
 * The scenario-driven Helm CLASSIC (ChartMuseum-protocol) suite (step 4b, RPS-294):
 * `registerPublishConsumeLoop(helmClassicAdapter)` wires the whole shared catalog into it. Plus
 * real-client tests the catalog loop itself cannot exercise, numbered "C" per the plan:
 *
 *  - "C1" the panel's own documented flow: `helm repo add` + `helm repo update` + `helm search
 *    repo` + `helm pull <repo>/<chart>`; and `helm cm-push` BY REPO NAME (reads the repositories
 *    file `helm repo add` wrote) with `--access-token` (the raw-deploy-token Bearer path).
 *  - "C2" `helm cm-push --force` does NOT bypass `allowOverride: false` (confirmed live).
 *  - "C3" a classic-pushed chart is not pullable via `helm pull oci://` (observation, the mirror
 *    of "HL4"/B-H1: the two Helm protocols share nothing but the coordinate namespace).
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { helmClassicAdapter } from '../../src/clients/helm-classic.js';
import { helmEnv, renderHelmRegistryConfig } from '../../src/clients/helm.js';
import { buildChart, writeChartFile } from '../../src/clients/helm-chart.js';
import {
  adminCredential,
  classicRepoUrl,
  indexEntry,
  ociChartRef,
  parseIndex,
  rawGetIndex,
} from '../../src/clients/helm-raw.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';

registerPublishConsumeLoop(helmClassicAdapter);

test(
  'helm-classic > C1: helm repo add + repo update + search repo + pull, and cm-push by repo ' +
    'name with --access-token (the raw deploy-token Bearer path)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const repoAlias = `repsy-${seeder.runId}`;
    const chart = `e2e-${seeder.runId}-c1`;
    const version = helmClassicAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`helm-c1-${seeder.runId}`);
    const built = await buildChart({ name: chart, version, marker: 'c1' });
    const tgzFile = await writeChartFile(work, built);

    // cm-push BY REPO NAME (below) reads the repositories file `helm repo add` writes, so add the
    // repo first.
    const repoAddResult = await run(
      'helm',
      [
        'repo',
        'add',
        repoAlias,
        classicRepoUrl(repo.name),
        '--username',
        token.username,
        '--password-stdin',
      ],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 30_000,
        redact: [token.token],
        label: 'helm-c1-repo-add',
        input: `${token.token}\n`,
      },
    );
    expect(repoAddResult.exitCode, `helm repo add: ${repoAddResult.command}`).toBe(0);

    // Now push the real chart via cm-push BY REPO NAME, with --access-token (the raw
    // deploy-token Bearer path, `handleBearerAuth`'s raw-token branch).
    const cmPushResult = await run(
      'helm',
      ['cm-push', tgzFile, repoAlias, '--access-token', token.token],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        redact: [token.token],
        label: 'helm-c1-cmpush',
      },
    );
    expect(cmPushResult.exitCode, `helm cm-push by repo name: ${cmPushResult.command}`).toBe(0);

    const repoUpdateResult = await run('helm', ['repo', 'update', repoAlias], {
      cwd: work,
      env: helmEnv(home),
      timeoutMs: 30_000,
      label: 'helm-c1-repo-update',
    });
    expect(repoUpdateResult.exitCode, `helm repo update: ${repoUpdateResult.command}`).toBe(0);

    const searchResult = await run('helm', ['search', 'repo', `${repoAlias}/`, '--versions'], {
      cwd: work,
      env: helmEnv(home),
      timeoutMs: 30_000,
      label: 'helm-c1-search',
    });
    expect(searchResult.exitCode, `helm search repo: ${searchResult.command}`).toBe(0);
    expect(searchResult.stdout, 'the pushed chart/version is listed').toContain(chart);
    expect(searchResult.stdout).toContain(version);

    // helm pull does not create --destination itself (see helm.ts's resolve()).
    const pulledDir = path.join(work, 'pulled');
    await fs.mkdir(pulledDir, { recursive: true });
    const pullResult = await run(
      'helm',
      ['pull', `${repoAlias}/${chart}`, '--version', version, '--destination', pulledDir],
      { cwd: work, env: helmEnv(home), timeoutMs: 30_000, label: 'helm-c1-pull' },
    );
    expect(pullResult.exitCode, `helm pull <repo>/<chart>: ${pullResult.command}`).toBe(0);
  },
);

test(
  'helm-classic > C2: cm-push --force does NOT bypass allowOverride:false',
  { tag: ['@settings', '@negative'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    await seeder.setSettings(repo.name, {
      privateRepo: true,
      allowOverride: false,
      releases: true,
      snapshots: true,
    });
    const credential = adminCredential();
    const chart = `e2e-${seeder.runId}-c2`;
    const version = helmClassicAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`helm-c2-${seeder.runId}`);
    const first = await buildChart({ name: chart, version, marker: 'c2-v1' });
    const firstFile = await writeChartFile(path.join(work, 'v1'), first);
    const firstPush = await run(
      'helm',
      [
        'cm-push',
        firstFile,
        classicRepoUrl(repo.name),
        '--username',
        credential.username ?? '',
        '--password',
        credential.password ?? '',
      ],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        redact: [credential.password ?? ''],
        label: 'helm-c2-push-v1',
      },
    );
    expect(firstPush.exitCode, 'the first push of a fresh coordinate succeeds').toBe(0);

    // cm-push repackages the chart before sending it (see helm-classic.ts's file header), so the
    // truly-stored digest is read back here rather than assumed to be `first.tgzDigest`.
    const indexAfterFirst = await rawGetIndex(repo.name, credential);
    const storedEntry = indexEntry(
      parseIndex(indexAfterFirst.body.toString('utf8')),
      chart,
      version,
    );
    expect(storedEntry, 'an index entry for the first push').toBeDefined();
    const storedDigest = storedEntry?.digest;

    const second = await buildChart({ name: chart, version, marker: 'c2-v2' });
    const secondFile = await writeChartFile(path.join(work, 'v2'), second);
    const forcedPush = await run(
      'helm',
      [
        'cm-push',
        secondFile,
        classicRepoUrl(repo.name),
        '--force',
        '--username',
        credential.username ?? '',
        '--password',
        credential.password ?? '',
      ],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        redact: [credential.password ?? ''],
        label: 'helm-c2-push-forced',
      },
    );
    expect(
      forcedPush.exitCode,
      'cm-push --force is refused server-side exactly like a plain re-push -- ?force=true is ' +
        'never read (confirmed live)',
    ).not.toBe(0);

    const indexRes = await rawGetIndex(repo.name, credential);
    const entryAfterForced = indexEntry(parseIndex(indexRes.body.toString('utf8')), chart, version);
    expect(entryAfterForced?.digest, 'the refused forced push left the digest unchanged').toBe(
      storedDigest,
    );
    expect(indexRes.body.toString('utf8')).not.toContain(second.tgzDigest);
  },
);

test(
  'helm-classic > C3: a classic-pushed chart is not pullable via helm pull oci:// (observation, ' +
    'mirrors HL4/B-H1 in reverse)',
  { tag: ['@settings'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.HELM, { privateRepo: true });
    const credential = adminCredential();
    const chart = `e2e-${seeder.runId}-c3`;
    const version = helmClassicAdapter.version('release');

    const { home, work } = await isolatedWorkDir(`helm-c3-${seeder.runId}`);
    const built = await buildChart({ name: chart, version, marker: 'c3' });
    const tgzFile = await writeChartFile(work, built);

    const pushResult = await run(
      'helm',
      [
        'cm-push',
        tgzFile,
        classicRepoUrl(repo.name),
        '--username',
        credential.username ?? '',
        '--password',
        credential.password ?? '',
      ],
      {
        cwd: work,
        env: helmEnv(home),
        timeoutMs: 60_000,
        redact: [credential.password ?? ''],
        label: 'helm-c3-push',
      },
    );
    expect(pushResult.exitCode).toBe(0);

    await renderHelmRegistryConfig(home, credential);
    const pulledOciDir = path.join(work, 'pulled-oci');
    await fs.mkdir(pulledOciDir, { recursive: true });
    const pullResult = await run(
      'helm',
      [
        'pull',
        ociChartRef(repo.name, chart),
        '--version',
        version,
        '--destination',
        pulledOciDir,
        '--plain-http',
      ],
      { cwd: work, env: helmEnv(home), timeoutMs: 30_000, label: 'helm-c3-pull-oci' },
    );
    expect(
      pullResult.exitCode,
      'a classic-pushed chart has no OCI manifest at all, so an OCI pull of it fails -- the two ' +
        'Helm protocols share nothing but the coordinate namespace',
    ).not.toBe(0);
  },
);
