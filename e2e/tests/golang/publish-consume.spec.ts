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
 * The scenario-driven golang suite (step 4d, RPS-294): `registerPublishConsumeLoop(golangAdapter)`
 * wires the whole shared catalog into golang, exactly like `tests/pypi/publish-consume.spec.ts` does
 * for pypi. Plus dedicated hand-built-`World`/raw-toolchain tests the catalog loop itself cannot
 * exercise -- every H-number below was confirmed live before this file was written (see
 * `README.md`'s "Go runner" section for the raw evidence):
 *
 *  - "go get + build + run prints the marker" (H6/H12): proves the hand-built zip is not merely
 *    byte-transportable but genuinely CONSUMABLE by the real toolchain end-to-end -- `go get`, then
 *    `go build`, then running the binary.
 *  - "a plain-http GOPROXY with embedded credentials is refused client-side" (H3/RPS-1229): the
 *    panel's own documented `go env -w GOPROXY="https://user:pass@..."` incantation
 *    (`golang-config.component.ts`) cannot work at all against a plain-http deployment -- confirmed
 *    live, `go` itself refuses before any request is sent.
 *  - "Sum/GoModSum match a real dirhash.Hash1 computation" (H2): cross-checks this harness's own
 *    `dirhashHash1` reference implementation against what a REAL `go mod download -json` reports.
 *  - "mixed-case module path real client round trip" (H18/RPS-1232): a real `go get` of a
 *    mixed-case module path still resolves, because the server lower-cases both the upload and the
 *    lookup.
 *  - "wire sequence through the TLS shim" (H8/H19): a credentialed real consume's request trace
 *    names exactly the routes `AbstractGoProtocolFacade.download` implements.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { golangAdapter, goEnv, renderConsumerProject } from '../../src/clients/golang.js';
import {
  adminCredential,
  buildModuleZip,
  MODULE_DOMAIN,
  rawUpload,
} from '../../src/clients/golang-raw.js';
import { shimTraceSoFar } from '../../src/clients/golang-tls-shim.js';
import { clientEnv } from '../../src/clients/client-env.js';
import { isolatedWorkDir, run } from '../../src/clients/exec.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';

registerPublishConsumeLoop(golangAdapter);

test(
  'golang > go get + build + run prints the marker (H6/H12)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const admin = adminCredential();
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-gogetbuild`;
    const version = golangAdapter.version('release');

    const built = await buildModuleZip({ modulePath, version });
    const uploadRes = await rawUpload(repo.name, admin, built);
    expect(uploadRes.status, `module upload: ${uploadRes.status}`).toBe(200);

    const { home, work } = await isolatedWorkDir(`golang-gogetbuild-${seeder.runId}`);
    await renderConsumerProject(work, modulePath);

    const goGetEnv = await goEnv(home, {}, repo.name);
    const getResult = await run('go', ['get', `${modulePath}@${version}`], {
      cwd: work,
      env: goGetEnv,
      timeoutMs: 120_000,
      label: 'golang-gogetbuild-get',
    });
    expect(getResult.exitCode, `go get: ${getResult.command}`).toBe(0);

    const binaryPath = path.join(work, 'consumer');
    const buildResult = await run('go', ['build', '-o', binaryPath, '.'], {
      cwd: work,
      env: goGetEnv,
      timeoutMs: 120_000,
      label: 'golang-gogetbuild-build',
    });
    expect(buildResult.exitCode, `go build: ${buildResult.command}`).toBe(0);

    const runResult = await run(binaryPath, [], {
      cwd: work,
      env: clientEnv(home),
      timeoutMs: 30_000,
      label: 'golang-gogetbuild-run',
    });
    expect(runResult.exitCode, `run consumer: ${runResult.command}`).toBe(0);
    expect(runResult.stdout.trim(), 'the consumer prints the published Marker').toBe(built.marker);
  },
);

test(
  'golang > a plain-http GOPROXY with embedded credentials is refused client-side, never sent ' +
    '(H3/RPS-1229)',
  { tag: ['@auth', '@negative'] },
  async ({ seeder }) => {
    // On a TLS stack (README.md "TLS stack", RPS-1474) the plain listener is still there, so the refusal is
    // pinned against it: `plainRepoBaseUrl` is `repoBaseUrl` on a stack without TLS.
    test.skip(
      new URL(env.plainRepoBaseUrl).protocol !== 'http:',
      'this pins the plain-http-specific client refusal; irrelevant against an https-only target',
    );

    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-plainhttpcreds`;
    const version = golangAdapter.version('release');

    const admin = adminCredential();
    const built = await buildModuleZip({ modulePath, version });
    await rawUpload(repo.name, admin, built);

    // Exactly the panel's own documented incantation (golang-config.component.ts): userinfo
    // embedded directly in a PLAIN http:// GOPROXY URL -- deliberately bypassing the TLS shim.
    const insecureProxy = `http://token:${token.token}@${new URL(env.plainRepoBaseUrl).host}/${repo.name},off`;

    const { home, work } = await isolatedWorkDir(`golang-plainhttpcreds-${seeder.runId}`);
    const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
      cwd: work,
      env: clientEnv(home, {
        GOPATH: path.join(home, 'gopath'),
        GOMODCACHE: path.join(home, 'gomodcache'),
        GOCACHE: path.join(home, 'gocache'),
        GOENV: 'off',
        GOTOOLCHAIN: 'local',
        GOWORK: 'off',
        CGO_ENABLED: '0',
        GOPROXY: insecureProxy,
        GONOSUMDB: MODULE_DOMAIN,
      }),
      timeoutMs: 60_000,
      redact: [token.token],
      label: 'golang-plainhttpcreds',
    });

    expect(result.exitCode, 'go mod download exits non-zero, never sends the request').toBe(1);
    const parsed = JSON.parse(result.stdout) as { Error?: string };
    expect(
      parsed.Error,
      'the client refuses before any request, citing the insecure URL',
    ).toContain('refusing to pass credentials to insecure URL');
  },
);

test(
  'golang > Sum/GoModSum match a real dirhash.Hash1 computation (H2)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const admin = adminCredential();
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-dirhash`;
    const version = golangAdapter.version('release');

    const built = await buildModuleZip({ modulePath, version });
    const uploadRes = await rawUpload(repo.name, admin, built);
    expect(uploadRes.status).toBe(200);

    const { home, work } = await isolatedWorkDir(`golang-dirhash-${seeder.runId}`);
    const goGetEnv = await goEnv(home, {}, repo.name);
    const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
      cwd: work,
      env: goGetEnv,
      timeoutMs: 60_000,
      label: 'golang-dirhash',
    });
    expect(result.exitCode, `go mod download: ${result.command}`).toBe(0);

    const parsed = JSON.parse(result.stdout) as { Sum?: string; GoModSum?: string };
    expect(
      parsed.Sum,
      'the real dirhash of the zip matches this harness’s own reference impl',
    ).toBe(built.h1Zip);
    expect(
      parsed.GoModSum,
      'the real dirhash of go.mod alone matches this harness’s own reference impl',
    ).toBe(built.h1Mod);
  },
);

test(
  'golang > mixed-case module path real client round trip (H18/RPS-1232)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const admin = adminCredential();
    // Mixed case only in the LAST path segment (the domain itself must stay lower-case/safe per the
    // README's "Module Path Convention"): exercises Go's own module-path case sensitivity without
    // risking a VCS-discoverable domain.
    const modulePath = `${MODULE_DOMAIN}/E2E-${seeder.runId}-MixedCase`;
    const version = golangAdapter.version('release');

    const built = await buildModuleZip({ modulePath, version });
    const uploadRes = await rawUpload(repo.name, admin, built);
    expect(uploadRes.status, 'the raw upload accepts the mixed-case path literally').toBe(200);

    const { home, work } = await isolatedWorkDir(`golang-mixedcase-${seeder.runId}`);
    const goGetEnv = await goEnv(home, {}, repo.name);
    const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
      cwd: work,
      env: goGetEnv,
      timeoutMs: 60_000,
      label: 'golang-mixedcase',
    });
    expect(result.exitCode, `go mod download of the mixed-case path: ${result.command}`).toBe(0);

    const parsed = JSON.parse(result.stdout) as { Path?: string; Zip?: string };
    expect(parsed.Path, 'go reports back the ORIGINAL mixed-case path it was given').toBe(
      modulePath,
    );
    const bytes = await fs.readFile(parsed.Zip as string);
    expect(bytes).toHaveLength(built.bytes.length);
  },
);

test(
  'golang > wire sequence through the TLS shim: .info, .mod, .zip for an exact-version consume ' +
    '(H8/H19)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    test.skip(
      new URL(env.repoBaseUrl).protocol !== 'http:',
      'the shim only engages for a credentialed request against a plain-http target (its https twin follows)',
    );

    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const credential = {
      transport: 'basic' as const,
      username: token.username,
      password: token.token,
      kind: 'token' as const,
    };
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-wiretrace`;
    const version = golangAdapter.version('release');

    const admin = adminCredential();
    const built = await buildModuleZip({ modulePath, version });
    await rawUpload(repo.name, admin, built);

    const before = (await shimTraceSoFar())?.length ?? 0;

    const { home, work } = await isolatedWorkDir(`golang-wiretrace-${seeder.runId}`);
    const goGetEnv = await goEnv(home, credential, repo.name);
    const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
      cwd: work,
      env: goGetEnv,
      timeoutMs: 60_000,
      redact: [token.token],
      label: 'golang-wiretrace',
    });
    expect(result.exitCode, `go mod download: ${result.command}`).toBe(0);

    const trace = (await shimTraceSoFar()) ?? [];
    const newRequests = trace.slice(before);
    expect(newRequests.length, 'the shim recorded new requests for this consume').toBeGreaterThan(
      0,
    );
    expect(
      newRequests.every((entry) => entry.hasAuthorization),
      'every proxied request carried the credential',
    ).toBe(true);

    const paths = newRequests.map((entry) => entry.path);
    expect(
      paths.some((p) => p.endsWith('.info')),
      `.info was requested: ${paths.join(', ')}`,
    ).toBe(true);
    expect(
      paths.some((p) => p.endsWith('.mod')),
      `.mod was requested: ${paths.join(', ')}`,
    ).toBe(true);
    expect(
      paths.some((p) => p.endsWith('.zip')),
      `.zip was requested: ${paths.join(', ')}`,
    ).toBe(true);
  },
);

test(
  "golang > credentials in GOPROXY over Repsy's own TLS: .info, .mod and .zip, no shim (RPS-1474)",
  { tag: ['@smoke', '@tls'] },
  async ({ seeder }) => {
    test.skip(
      new URL(env.repoBaseUrl).protocol !== 'https:',
      'the https twin of the shim case above: needs a TLS stack (README.md "TLS stack")',
    );

    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const credential = {
      transport: 'basic' as const,
      username: token.username,
      password: token.token,
      kind: 'token' as const,
    };
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-tlsconsume`;
    const version = golangAdapter.version('release');

    const admin = adminCredential();
    const built = await buildModuleZip({ modulePath, version });
    expect((await rawUpload(repo.name, admin, built)).status).toBe(200);

    const before = (await shimTraceSoFar())?.length ?? 0;

    const { home, work } = await isolatedWorkDir(`golang-tlsconsume-${seeder.runId}`);
    // The panel's own documented incantation: userinfo in an https GOPROXY URL, straight at Repsy's TLS
    // listener (`goEnv` embeds it because the target is https), trusting the stack's CA through the
    // SSL_CERT_FILE the runner was given.
    const goGetEnv = await goEnv(home, credential, repo.name);
    expect(goGetEnv.GOPROXY, 'the proxy is Repsy itself, over TLS').toMatch(
      new RegExp(`^https://[^@]+@${new URL(env.repoBaseUrl).host}/${repo.name},off$`),
    );
    const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
      cwd: work,
      env: goGetEnv,
      timeoutMs: 60_000,
      redact: [token.token],
      label: 'golang-tlsconsume',
    });
    expect(result.exitCode, `go mod download: ${result.command}`).toBe(0);

    // `go mod download -json` reports the three files it fetched (.info, .mod, .zip) as cache paths.
    const parsed = JSON.parse(result.stdout) as {
      Info?: string;
      GoMod?: string;
      Zip?: string;
      Version?: string;
    };
    expect(parsed.Version).toBe(version);
    for (const file of [parsed.Info, parsed.GoMod, parsed.Zip]) {
      expect(file, 'a file go downloaded').toBeTruthy();
      await expect(fs.stat(file as string), `${file} is in the module cache`).resolves.toBeTruthy();
    }
    expect(await fs.readFile(parsed.Zip as string)).toHaveLength(built.bytes.length);
    expect(
      (await shimTraceSoFar())?.length ?? 0,
      'the consume never went through the in-process TLS shim',
    ).toBe(before);
  },
);
