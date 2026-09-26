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
 * The Go module proxy client adapter (step 4d, RPS-294). Unlike every other protocol in this harness,
 * Go has NO official publisher at all: `repsy-protocols/golang/README.md`'s "Uploading a Module"
 * section is explicit that Repsy is a push registry whose only documented publisher is a single
 * `curl -T` ("Repsy does not run `go mod` commands. You build the zip locally and upload it with a
 * single `curl`"), and the panel's own `golang-config.component.ts` teaches the identical incantation.
 * So `publish`/`seedPublish` drive the REAL `curl` binary (the maven/npm/nuget "hide the status behind
 * an exit code" pattern does not even apply: `curl -w '%{http_code}'` reports the raw HTTP status
 * directly, confirmed live -- `--fail-with-body` still writes the response body to `-o` and prints the
 * status code on a 4xx/5xx, exit 22, so there is no need for a companion raw-HTTP probe the way
 * pypi's/nuget's/cargo's `publish()` needs one). `resolve` drives the REAL `go` toolchain (`go mod
 * download -json`), the consume side every ecosystem in this harness gets.
 *
 * Every module zip is hand-built (`golang-raw.ts`'s `buildModuleZip`, `fflate` -- never `go build`/
 * `go mod`), with a fresh random marker packed into `hello.go`/`e2e-marker.txt`, so two publishes of
 * one coordinate never share content; `AdapterResult.contentSha256` is the sha256 of the WHOLE zip
 * file (`go mod download`'s cache renames the proxy response into place byte-for-byte after checking
 * every zip entry's `<path>@<version>/` prefix, confirmed live -- comparing whole-file digests is what
 * that renamed, still-packed cache file can actually be checked against).
 *
 * There is deliberately NO redeploy/prerelease-sibling trick the way cargo's adapter needs one:
 * version immutability on Go is unconditional (confirmed live/H9: a duplicate-version `PUT` is always
 * `409`, `allowOverride` is never read anywhere in either Go package, grep-confirmed), so
 * `no-override`/`override` both simply pin `409` in `catalog.ts` and every scenario's own `publish()`
 * call already IS the real, final attempt -- there is nothing left to probe afterwards.
 *
 * Credential mapping: a `MaterializedCredential`'s `username`/`password` are passed to `curl -u`
 * verbatim, and to `go`'s `GOPROXY` URL userinfo (through `golang-tls-shim.ts`'s `goProxyUrlFor`,
 * which also decides whether a credentialed consume needs the TLS shim at all -- see that file's
 * header for H3/H4). A token credential's actual generated username (never the panel docs' friendly
 * literal `token`) is used throughout: `ProtocolAuthService.handleBasicAuth` tries the PASSWORD as a
 * deploy token first regardless of what the username is, confirmed live, so this is exactly as valid
 * as the docs' convention and more realistic (a real deploy token's username is server-generated).
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import { expect } from '@playwright/test';
import mustache from 'mustache';

import type { AdapterResult, ProtocolAdapter } from '../scenarios/adapter.js';
import { outcomeForStatus } from '../scenarios/types.js';
import type { SeedResult, World } from '../scenarios/world.js';
import { goProxyUrlFor } from './golang-tls-shim.js';
import {
  adminCredential,
  type BuiltGoModule,
  buildModuleZip,
  goVersion,
  infoRelPath,
  listRelPath,
  MODULE_DOMAIN,
  modRelPath,
  packageName as rawPackageName,
  parseInfo,
  parseVersionList,
  rawGet,
  renderGoModText,
  sha256Hex,
  TEMPLATES_DIR,
  uploadUrl,
  zipRelPath,
} from './golang-raw.js';
import { clientEnv } from './client-env.js';
import { isolatedWorkDir, run } from './exec.js';

const PUBLISH_TIMEOUT_MS = 60_000;
const CONSUME_TIMEOUT_MS = 120_000;

/** Every env var isolating one `go` invocation: a private `HOME`/`GOPATH`/module cache/build cache, no
 *  user `GOENV` config file, a pinned local toolchain (never a network toolchain fetch, confirmed
 *  live/H7), no workspace file, `GONOSUMDB` scoped to this harness's own module domain (Repsy has no
 *  checksum database at all -- `repsy-protocols/golang/README.md`'s "Checksum Database" section), and
 *  `GOPROXY` built by `goProxyUrlFor` (plain `env.repoBaseUrl` for an anonymous/already-https target,
 *  the TLS shim for a credentialed plain-http one). `,off` as the fallback (never `,direct`), matching
 *  the panel's own documented incantation, so a module this harness did not itself publish fails
 *  loudly instead of Go attempting real VCS discovery for `e2e.repsy.test` (which never resolves,
 *  `golang-raw.ts`'s `MODULE_DOMAIN` doc comment). */
async function goEnv(
  home: string,
  credential: World['credential'],
  repoName: string,
): Promise<NodeJS.ProcessEnv> {
  const { proxyUrl, certFile } = await goProxyUrlFor(repoName, credential);
  const result = clientEnv(home, {
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
    GOPROXY: `${proxyUrl},off`,
    GONOSUMDB: MODULE_DOMAIN,
    NO_PROXY: '127.0.0.1,localhost',
    HTTP_PROXY: '',
    HTTPS_PROXY: '',
  });
  if (certFile) {
    result.SSL_CERT_FILE = certFile;
  }
  return result;
}

interface PublishRun {
  exitCode: number;
  httpStatus: number;
  command: string;
  built: BuiltGoModule;
}

/**
 * Hand-builds a fresh module zip and runs the real `curl -T`, exactly the panel's documented
 * incantation (this file's header): `-u user:secret` omitted entirely for `anonymous` (never sent as
 * empty-string credentials), `Content-Sha256` and an explicit `Content-Type: application/zip` always
 * sent (the maven adapter's lesson: a body-less content type can be consumed as form data), uploading
 * to the `.zip`-suffixed URL the panel itself teaches (R2/H14 in `registry-rules.spec.ts` separately
 * pin that the backend README's own suffix-less spelling is ALSO accepted).
 */
async function publishWithClient(world: World, label: string): Promise<PublishRun> {
  const { home, work } = await isolatedWorkDir(label);
  const { packageName: modulePath, version } = world.publishTarget;

  const built = await buildModuleZip({ modulePath, version });
  const zipFile = path.join(work, 'module.zip');
  await fs.writeFile(zipFile, built.bytes);
  const responseFile = path.join(work, 'response.body');

  const credential = world.credential;
  const secrets = credential.password ? [credential.password] : [];
  const args = [
    '-sS',
    '--fail-with-body',
    '--max-time',
    '60',
    '-o',
    responseFile,
    '-w',
    '%{http_code}',
  ];
  if (credential.transport === 'basic') {
    args.push('-u', `${credential.username ?? ''}:${credential.password ?? ''}`);
  }
  args.push(
    '-T',
    zipFile,
    '-H',
    `Content-Sha256: ${built.sha256Hex}`,
    '-H',
    'Content-Type: application/zip',
    uploadUrl(world.repoName, modulePath, version),
  );

  const execResult = await run('curl', args, {
    cwd: work,
    env: clientEnv(home),
    timeoutMs: PUBLISH_TIMEOUT_MS,
    redact: secrets,
    label,
  });

  // `-w '%{http_code}'` is curl's OWN report of the request's raw HTTP status, printed to stdout
  // whether the request succeeded or `--fail-with-body` made curl exit non-zero (confirmed live) -- 0
  // only for a request that never got a response at all (not expected against this harness's own
  // stack).
  const httpStatus = Number.parseInt(execResult.stdout.trim(), 10) || 0;

  return { exitCode: execResult.exitCode, httpStatus, command: execResult.command, built };
}

export async function publish(world: World): Promise<AdapterResult> {
  const published = await publishWithClient(world, `golang-publish-${world.scenario.id}`);
  return {
    outcome: outcomeForStatus(published.httpStatus),
    httpStatus: published.httpStatus,
    clientExitCode: published.exitCode,
    command: published.command,
    contentSha256: published.built.sha256Hex,
  };
}

/**
 * The pre-publish for a scenario whose own credential cannot publish, or that redeploys a coordinate
 * (`reuseCoordinates`). Only the real client runs, mirroring `clients/pypi.ts`'s/`clients/cargo.ts`'s
 * `seedPublish`.
 */
export async function seedPublish(world: World): Promise<SeedResult> {
  const published = await publishWithClient(world, `golang-seed-${world.scenario.id}`);
  if (published.exitCode !== 0 || published.httpStatus !== 200) {
    throw new Error(
      `golang adapter: pre-publish for scenario "${world.scenario.id}" failed unexpectedly ` +
        `(curl exit ${published.exitCode}, http ${published.httpStatus}); its "consume: ok" ` +
        'expectation depends on this module actually existing.',
    );
  }
  return { contentSha256: published.built.sha256Hex };
}

interface GoModDownloadJson {
  Path?: string;
  Version?: string;
  Zip?: string;
  Error?: string;
}

export async function resolve(world: World): Promise<AdapterResult> {
  const { home, work } = await isolatedWorkDir(`golang-con-${world.scenario.id}`);
  const { packageName: modulePath, version } = world.consumeTarget;

  const secrets = world.credential.password ? [world.credential.password] : [];
  const env = await goEnv(home, world.credential, world.repoName);
  const execResult = await run('go', ['mod', 'download', '-json', `${modulePath}@${version}`], {
    cwd: work,
    env,
    timeoutMs: CONSUME_TIMEOUT_MS,
    redact: secrets,
    label: `golang-consume-${world.scenario.id}`,
  });

  let contentSha256: string | undefined;
  let resolvedFile: string | undefined;
  try {
    const parsed = JSON.parse(execResult.stdout) as GoModDownloadJson;
    if (parsed.Zip) {
      const bytes = await fs.readFile(parsed.Zip);
      contentSha256 = sha256Hex(bytes);
      resolvedFile = parsed.Zip;
    }
  } catch {
    // `go mod download -json` did not print a (valid) JSON object -- exitCode/the raw probe below
    // already describe what happened; there is simply nothing resolved to compare.
  }

  // The auth-only companion probe (mirrors pypi's/cargo's own `resolve()`): a plain `.info` GET never
  // touches the zip bytes `go mod download` itself just fetched.
  const rawRes = await rawGet(world.repoName, world.credential, infoRelPath(modulePath, version));

  return {
    outcome: outcomeForStatus(rawRes.status),
    httpStatus: rawRes.status,
    clientExitCode: execResult.exitCode,
    command: execResult.command,
    contentSha256,
    resolvedFile,
  };
}

/** `@v/list`'s own body hash plus every listed version's `.info`/`.mod`/`.zip` content hash, for
 *  `ProtocolAdapter.fingerprint`/`expectNothingStored`. Scoped to the one module a scenario's publish
 *  targets, exactly like `CargoFingerprint`/`PypiFingerprint`. */
export interface GolangFingerprint {
  listSha256: string;
  files: Record<string, string>;
}

async function fingerprint(world: World): Promise<GolangFingerprint> {
  const admin = adminCredential();
  const modulePath = world.publishTarget.packageName;

  const listRes = await rawGet(world.repoName, admin, listRelPath(modulePath));
  const versions = parseVersionList(listRes.body);
  const listSha256 = sha256Hex(listRes.body);

  const files: Record<string, string> = {};
  for (const version of versions) {
    const paths: [string, string][] = [
      ['info', infoRelPath(modulePath, version)],
      ['mod', modRelPath(modulePath, version)],
      ['zip', zipRelPath(modulePath, version)],
    ];
    for (const [key, relPath] of paths) {
      const res = await rawGet(world.repoName, admin, relPath);
      files[`${version}/${key}`] =
        res.status === 200 ? sha256Hex(res.body) : `status:${res.status}`;
    }
  }

  return { listSha256, files };
}

async function expectNothingStored(world: World, before: GolangFingerprint): Promise<void> {
  const after = await fingerprint(world);
  expect(after, 'a refused publish must leave the module exactly as it was').toEqual(before);

  const modulePath = world.publishTarget.packageName;
  const version = world.publishTarget.version;
  const alreadyExisted = Object.keys(before.files).some((key) => key.startsWith(`${version}/`));
  if (!alreadyExisted) {
    const admin = adminCredential();
    for (const relPath of [
      infoRelPath(modulePath, version),
      modRelPath(modulePath, version),
      zipRelPath(modulePath, version),
    ]) {
      const res = await rawGet(world.repoName, admin, relPath);
      expect(res.status, `the refused version's "${relPath}" was never stored`).toBe(404);
    }
    const listRes = await rawGet(world.repoName, admin, listRelPath(modulePath));
    expect(parseVersionList(listRes.body), 'the refused version is not listed').not.toContain(
      version,
    );
  }
}

/**
 * Run only after BOTH the publish and the consume of one scenario succeeded: the served `.info` names
 * exactly the published version and carries a `Time` that parses as a real instant; the served `.mod`
 * bytes equal the rendered `go.template.mod` text (the marker never touches `go.mod`, so this is
 * deterministic regardless of which publish attempt actually landed); the served `.zip` sha256 equals
 * the published/seeded bytes and the resolved (cached) file's own sha256; `@v/list` lists the version;
 * every route's `Content-Type` matches `AbstractGoDownloadProtocolMethodHandler.resolveContentType`.
 */
async function afterSuccessfulRoundTrip(
  world: World,
  published: AdapterResult,
  resolved: AdapterResult,
): Promise<void> {
  const admin = adminCredential();
  const modulePath = world.consumeTarget.packageName;
  const version = world.consumeTarget.version;

  const infoRes = await rawGet(world.repoName, admin, infoRelPath(modulePath, version));
  expect(infoRes.status, 'the .info file exists for the published version').toBe(200);
  expect(infoRes.contentType, 'the .info file is served as application/json').toBe(
    'application/json',
  );
  const info = parseInfo(infoRes.body);
  expect(info.Version, 'the .info Version matches the published version').toBe(version);
  expect(
    Number.isNaN(new Date(info.Time).getTime()),
    'the .info Time parses as a real instant',
  ).toBe(false);

  const modRes = await rawGet(world.repoName, admin, modRelPath(modulePath, version));
  expect(modRes.status, 'the .mod file exists').toBe(200);
  expect(modRes.contentType, 'the .mod file is served as text/plain').toBe('text/plain');
  const expectedGoMod = await renderGoModText(modulePath);
  expect(modRes.body.toString('utf8'), 'the served go.mod matches what was built').toBe(
    expectedGoMod,
  );

  const zipRes = await rawGet(world.repoName, admin, zipRelPath(modulePath, version));
  expect(zipRes.status, 'the .zip file exists').toBe(200);
  expect(zipRes.contentType, 'the .zip file is served as application/octet-stream').toBe(
    'application/octet-stream',
  );
  const expectedSha =
    published.outcome === 'ok' ? published.contentSha256 : world.seeded?.contentSha256;
  expect(sha256Hex(zipRes.body), 'the served zip matches the published bytes').toBe(expectedSha);
  expect(resolved.contentSha256, 'the resolved zip matches the served one').toBe(expectedSha);

  const listRes = await rawGet(world.repoName, admin, listRelPath(modulePath));
  expect(listRes.contentType, 'the version list is served as text/plain').toBe('text/plain');
  expect(parseVersionList(listRes.body), '@v/list carries the published version').toContain(
    version,
  );
}

/** Renders the tiny consumer module (`consumer-go.template.mod`/`consumer-main.template.go`) that
 *  imports `modulePath` and prints its `Marker` -- used only by the dedicated real-client
 *  go-get-and-build test in `tests/golang/publish-consume.spec.ts` (H6), never by the catalog loop. */
export async function renderConsumerProject(work: string, modulePath: string): Promise<void> {
  const goModTemplate = await fs.readFile(
    path.join(TEMPLATES_DIR, 'consumer-go.template.mod'),
    'utf8',
  );
  const mainTemplate = await fs.readFile(
    path.join(TEMPLATES_DIR, 'consumer-main.template.go'),
    'utf8',
  );
  await fs.writeFile(path.join(work, 'go.mod'), goModTemplate, 'utf8');
  await fs.writeFile(
    path.join(work, 'main.go'),
    mustache.render(mainTemplate, { modulePath }),
    'utf8',
  );
}

export { goEnv };

export const golangAdapter: ProtocolAdapter<GolangFingerprint> = {
  protocol: 'golang',
  client: { name: 'curl/go', publishVerb: 'upload (curl -T)', consumeVerb: 'go mod download' },

  packageName: (runId, scenario) => rawPackageName(runId, scenario),
  // Go has no release/prerelease repo-setting distinction (`releases`/`snapshots` are never read by
  // any Go code, grep-confirmed), so `versionType` is ignored, same as pypi/docker/helm.
  version: () => goVersion(),

  publish,
  resolve,
  seedPublish,

  fingerprint,
  expectNothingStored,
  afterSuccessfulRoundTrip,
};
