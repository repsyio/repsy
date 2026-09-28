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
import { repoPath } from '../../src/repo-url.js';
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
    const insecureProxy = `http://token:${token.token}@${new URL(env.plainRepoBaseUrl).host}/${repoPath(repo.name)},off`;

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
      new RegExp(`^https://[^@]+@${new URL(env.repoBaseUrl).host}/${repoPath(repo.name)},off$`),
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

test(
  'golang > .netrc authentication for module download (real-client)',
  { tag: ['@auth'] },
  async ({ seeder }) => {
    // Go's net/http client respects ~/.netrc files for HTTP Basic Authentication.
    // This test verifies that go mod download can authenticate to a private repo
    // using only a .netrc file, without URL-embedded credentials.
    // See: https://golang.org/pkg/net/#ParseNetrc
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: true });
    const token = await seeder.createToken(repo.name, { readOnly: false });
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-netrc`;
    const version = golangAdapter.version('release');

    const admin = adminCredential();
    const built = await buildModuleZip({ modulePath, version });
    const uploadRes = await rawUpload(repo.name, admin, built);
    expect(uploadRes.status, 'module upload succeeds').toBe(200);

    const { home, work } = await isolatedWorkDir(`golang-netrc-${seeder.runId}`);

    // Create a .netrc file in the home directory with token credentials
    // Format: machine <host> login <user> password <secret>
    // Go's net/http.Transport calls net.ParseNetrc() which reads this file.
    const hostUrl = new URL(env.repoBaseUrl);
    const netrcPath = path.join(home, '.netrc');
    const netrcContent = `machine ${hostUrl.hostname}\nlogin ${token.username}\npassword ${token.token}\n`;
    await fs.writeFile(netrcPath, netrcContent, { mode: 0o600 });

    // Use GOPROXY without embedded credentials; net/http will look up credentials in .netrc
    const proxyUrl = `${env.repoBaseUrl}/${repoPath(repo.name)},off`;
    const goEnvAnon = clientEnv(home, {
      GOPATH: path.join(home, 'gopath'),
      GOMODCACHE: path.join(home, 'gomodcache'),
      GOCACHE: path.join(home, 'gocache'),
      GOENV: 'off',
      GOTOOLCHAIN: 'local',
      GOWORK: 'off',
      CGO_ENABLED: '0',
      GOFLAGS: '',
      GOPRIVATE: '',
      GONOPROXY: '',
      GOINSECURE: '',
      GOPROXY: proxyUrl,
      GONOSUMDB: MODULE_DOMAIN,
      NO_PROXY: '127.0.0.1,localhost',
      HTTP_PROXY: '',
      HTTPS_PROXY: '',
    });

    const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
      cwd: work,
      env: goEnvAnon,
      timeoutMs: 60_000,
      label: 'golang-netrc',
    });

    expect(result.exitCode, `.netrc authentication succeeds: ${result.command}`).toBe(0);
    const parsed = JSON.parse(result.stdout) as { Zip?: string; Version?: string };
    expect(parsed.Version).toBe(version);
    expect(parsed.Zip, '.netrc-authenticated download should retrieve Zip').toBeTruthy();
  },
);

test(
  'golang > escape-encoded module path with uppercase letters (RPS-1232, real-client)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const admin = adminCredential();
    // Module path with multiple uppercase letters: example.com/CamelCase
    // These should be properly handled through Go's !-escape encoding
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-CapitalLetters`;
    const version = golangAdapter.version('release');

    const built = await buildModuleZip({ modulePath, version });
    const uploadRes = await rawUpload(repo.name, admin, built);
    expect(uploadRes.status, 'upload with uppercase in module path succeeds').toBe(200);

    const { home, work } = await isolatedWorkDir(`golang-escape-${seeder.runId}`);
    const goGetEnv = await goEnv(home, {}, repo.name);
    const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
      cwd: work,
      env: goGetEnv,
      timeoutMs: 60_000,
      label: 'golang-escape',
    });

    expect(result.exitCode, `go mod download of escaped path: ${result.command}`).toBe(0);
    const parsed = JSON.parse(result.stdout) as { Path?: string; Zip?: string };
    // Go should report back the original (mixed-case) path as requested
    expect(parsed.Path).toBe(modulePath);
    expect(parsed.Zip, 'escaped path download should retrieve zip').toBeTruthy();

    const bytes = await fs.readFile(parsed.Zip as string);
    expect(bytes).toHaveLength(built.bytes.length);
  },
);

test(
  'golang > retract directive: Repsy serves retracted versions normally (probe real-client)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    // Go's retraction mechanism is CLIENT-SIDE metadata in go.mod files.
    // Repsy has no knowledge of retractions - it simply serves modules by version.
    // This test verifies that Repsy does NOT enforce or recognize retraction directives.

    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const admin = adminCredential();
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-retract`;
    const v1 = 'v1.0.0';
    const v2 = 'v1.0.1';

    // Upload two versions normally
    const built1 = await buildModuleZip({ modulePath, version: v1 });
    const built2 = await buildModuleZip({ modulePath, version: v2 });

    expect((await rawUpload(repo.name, admin, built1)).status, 'v1 upload').toBe(200);
    expect((await rawUpload(repo.name, admin, built2)).status, 'v2 upload').toBe(200);

    const { home, work } = await isolatedWorkDir(`golang-retract-${seeder.runId}`);
    const goGetEnv = await goEnv(home, {}, repo.name);

    // Verify both versions are accessible (Repsy doesn't know one might be "retracted" by a consumer)
    for (const version of [v1, v2]) {
      const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
        cwd: work,
        env: goGetEnv,
        timeoutMs: 60_000,
        label: `golang-retract-${version}`,
      });

      expect(
        result.exitCode,
        `go mod download of ${version} succeeds on Repsy: ${result.command}`,
      ).toBe(0);
      const parsed = JSON.parse(result.stdout) as { Zip?: string; Version?: string };
      expect(parsed.Version).toBe(version);
      expect(parsed.Zip, `version ${version} is downloadable from Repsy`).toBeTruthy();
    }
  },
);

test(
  'golang > GOSUMDB variations: off vs. empty (probe)',
  { tag: ['@smoke'] },
  async ({ seeder }) => {
    const repo = await seeder.createRepo(RepoType.GOLANG, { privateRepo: false });
    const admin = adminCredential();
    const modulePath = `${MODULE_DOMAIN}/e2e-${seeder.runId}-gosumdb`;
    const version = golangAdapter.version('release');

    const built = await buildModuleZip({ modulePath, version });
    const uploadRes = await rawUpload(repo.name, admin, built);
    expect(uploadRes.status).toBe(200);

    // Test 1: GOSUMDB=off (disable checksum verification)
    {
      const { home, work } = await isolatedWorkDir(`golang-gosumdb-off-${seeder.runId}`);
      const goGetEnv = await goEnv(home, {}, repo.name);
      goGetEnv.GOSUMDB = 'off';

      const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
        cwd: work,
        env: goGetEnv,
        timeoutMs: 60_000,
        label: 'golang-gosumdb-off',
      });

      expect(result.exitCode, 'GOSUMDB=off allows download without sumdb: ' + result.command).toBe(
        0,
      );
      const parsed = JSON.parse(result.stdout) as { Zip?: string };
      expect(parsed.Zip).toBeTruthy();
    }

    // Test 2: Unset GOSUMDB (relies on GONOSUMDB to skip sumdb)
    {
      const { home, work } = await isolatedWorkDir(`golang-gosumdb-unset-${seeder.runId}`);
      const goGetEnv = await goEnv(home, {}, repo.name);
      delete goGetEnv.GOSUMDB;

      const result = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
        cwd: work,
        env: goGetEnv,
        timeoutMs: 60_000,
        label: 'golang-gosumdb-unset',
      });

      // With GONOSUMDB set to MODULE_DOMAIN (which it is from goEnv),
      // this should still succeed even without GOSUMDB=off
      expect(result.exitCode, 'GONOSUMDB skips sumdb verification: ' + result.command).toBe(0);
      const parsed = JSON.parse(result.stdout) as { Zip?: string };
      expect(parsed.Zip).toBeTruthy();
    }
  },
);
