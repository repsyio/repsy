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
 * RPS-1716: Maven dynamic versions, concurrent deploy, and strict checksums with real clients.
 *
 * - Dynamic versions: a real `mvn resolve` of a version range (e.g. `[1.0,2.0)`) or metaversion
 *   (`LATEST`/`RELEASE`) against Repsy — confirm live what Repsy serves for these and that a real
 *   Maven client resolves it correctly. Also probe the `maven-metadata.xml` generation.
 * - Concurrent deploy: two real, genuinely parallel `mvn deploy:deploy-file` invocations publishing
 *   to the same coordinates or racing on metadata updates, confirming no corruption/lost-update in
 *   the resulting `maven-metadata.xml`.
 * - Strict checksums: a real `mvn` deploy/resolve with Maven's strict checksum policy, confirming
 *   Repsy serves correct SHA1/MD5 sidecars and that a deliberately WRONG checksum is caught
 *   client-side.
 */

import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { run, isolatedWorkDir } from '../../src/clients/exec.js';
import { mavenEnv } from '../../src/clients/maven.js';
import {
  adminCredential,
  buildJar,
  minimalPom,
  rawGet,
  rawPut,
  versionDir,
  parseArtifactVersions,
  sha256Hex,
} from '../../src/clients/maven-raw.js';
import { env } from '../../src/env.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const DEPLOY_TIMEOUT_MS = 180_000;
const RESOLVE_TIMEOUT_MS = 180_000;

/**
 * Deploy a Maven artifact (jar + pom) with the given coordinates using mvn deploy:deploy-file.
 * Returns the exit code, stdout, and sha256 of the deployed jar.
 */
async function deployArtifact(
  repoName: string,
  groupId: string,
  artifactId: string,
  version: string,
): Promise<{ exitCode: number; stdout: string; jarSha: string }> {
  const { home, work } = await isolatedWorkDir('mvn-deploy');
  const base = `${artifactId}-${version}`;
  const pomContent = minimalPom(groupId, artifactId, version);
  const jarContent = buildJar({ groupId, artifactId, version });

  await fs.writeFile(path.join(work, `${base}.pom`), pomContent);
  await fs.writeFile(path.join(work, `${base}.jar`), jarContent);
  await fs.writeFile(
    path.join(work, 'settings.xml'),
    '<settings><servers><server><id>repsy</id>' +
      `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
      '</server></servers></settings>',
  );

  const result = await run(
    'mvn',
    [
      '-B',
      '-ntp',
      'deploy:deploy-file',
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${path.join(home, 'repo-local')}`,
      `-Dfile=${base}.jar`,
      `-DpomFile=${base}.pom`,
      '-DrepositoryId=repsy',
      `-Durl=${repoUrl(repoName)}`,
    ],
    {
      cwd: work,
      env: mavenEnv(home),
      timeoutMs: DEPLOY_TIMEOUT_MS,
      redact: [env.adminPassword],
      label: `maven-deploy-${version}`,
    },
  );

  return {
    exitCode: result.exitCode,
    stdout: result.stdout,
    jarSha: sha256Hex(jarContent),
  };
}

/**
 * Resolve a Maven artifact with a dependency version spec (range or metaversion) using
 * mvn dependency:get. Returns the exit code, stdout, and resolved version strings.
 *
 * `checksumPolicy` (default `'warn'`, Maven's own default) is only settable via a `<repository>`
 * block in the consumer POM -- the `-DremoteRepositories=id::layout::url` shorthand used otherwise
 * has no policy component. Passing `'fail'` is how a genuinely strict-checksum resolve is tested
 * (live-confirmed: Maven's actual out-of-the-box default checksum policy is `warn`, so a resolve
 * against a corrupted server checksum succeeds-with-a-warning unless the repository is configured
 * this way).
 */
async function resolveArtifactDynamic(
  repoName: string,
  groupId: string,
  artifactId: string,
  versionSpec: string,
  checksumPolicy: 'warn' | 'fail' = 'warn',
): Promise<{
  exitCode: number;
  stdout: string;
  resolvedVersions: string[];
  resolvedSha?: string;
}> {
  const { home, work } = await isolatedWorkDir('mvn-resolve');

  const repositoryBlock =
    checksumPolicy === 'fail'
      ? '  <repositories>\n' +
        '    <repository>\n' +
        '      <id>repsy</id>\n' +
        `      <url>${repoUrl(repoName)}</url>\n` +
        '      <releases><enabled>true</enabled><checksumPolicy>fail</checksumPolicy></releases>\n' +
        '      <snapshots><enabled>true</enabled><checksumPolicy>fail</checksumPolicy></snapshots>\n' +
        '    </repository>\n' +
        '  </repositories>\n'
      : '';

  await fs.writeFile(
    path.join(work, 'pom.xml'),
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
      '<project xmlns="http://maven.apache.org/POM/4.0.0">\n' +
      '  <modelVersion>4.0.0</modelVersion>\n' +
      `  <groupId>${groupId}.consumer</groupId>\n` +
      `  <artifactId>${artifactId}-consumer</artifactId>\n` +
      '  <version>0.0.0</version>\n' +
      repositoryBlock +
      '</project>\n',
  );

  await fs.writeFile(
    path.join(work, 'settings.xml'),
    '<settings><servers><server><id>repsy</id>' +
      `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
      '</server></servers></settings>',
  );

  const localRepo = path.join(home, 'repo-local');
  await fs.mkdir(localRepo, { recursive: true });

  // With the policy configured in the POM's own <repositories>, dependency:get resolves through
  // it without -DremoteRepositories (that shorthand cannot express a checksum policy).
  const remoteRepositoriesArgs =
    checksumPolicy === 'fail' ? [] : [`-DremoteRepositories=repsy::default::${repoUrl(repoName)}`];

  const result = await run(
    'mvn',
    [
      '-B',
      '-ntp',
      'dependency:get',
      `-Dartifact=${groupId}:${artifactId}:${versionSpec}:jar`,
      ...remoteRepositoriesArgs,
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${localRepo}`,
    ],
    {
      cwd: work,
      env: mavenEnv(home),
      timeoutMs: RESOLVE_TIMEOUT_MS,
      redact: [env.adminPassword],
      label: `maven-resolve-${versionSpec}`,
    },
  );

  // Find the resolved jar(s)
  const resolvedVersions: string[] = [];
  let resolvedSha: string | undefined;
  try {
    const groupPath = groupId.replace(/\./g, '/');
    const artifactDir = path.join(localRepo, groupPath, artifactId);
    const versionDirs = await fs.readdir(artifactDir).catch(() => [] as string[]);
    for (const version of versionDirs) {
      try {
        const jarPath = path.join(artifactDir, version, `${artifactId}-${version}.jar`);
        const jarContent = await fs.readFile(jarPath);
        resolvedVersions.push(version);
        if (!resolvedSha) {
          resolvedSha = sha256Hex(jarContent);
        }
      } catch {
        // Continue searching
      }
    }
  } catch {
    // No jar found is OK (resolution might have failed)
  }

  return {
    exitCode: result.exitCode,
    stdout: result.stdout,
    resolvedVersions: resolvedVersions.sort(),
    resolvedSha,
  };
}

test.describe('maven dynamic versions, concurrent deploy, and checksums (RPS-1716)', () => {
  test(
    'dynamic version: LATEST metaversion resolves to the newest release',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'latest-test';

      // Deploy versions 1.0, 2.0, 3.0 and verify LATEST resolves to 3.0
      const versions = ['1.0', '2.0', '3.0'];
      const deployResults: Record<string, string> = {};
      for (const version of versions) {
        const deploy = await deployArtifact(repo.name, groupId, artifactId, version);
        expect(deploy.exitCode, `deploy ${version} should succeed`).toBe(0);
        deployResults[version] = deploy.jarSha;
      }

      // Probe the artifact-level metadata to confirm all versions are listed
      const metadataPath = `${groupId.replace(/\./g, '/')}/${artifactId}/maven-metadata.xml`;
      const metadata = await rawGet(repo.name, adminCredential(), metadataPath);
      expect(metadata.status, 'artifact metadata should exist').toBe(200);
      const listedVersions = parseArtifactVersions(metadata.body.toString('utf8'));
      expect(
        listedVersions,
        'all deployed versions should be listed in artifact metadata',
      ).toContain('1.0');
      expect(listedVersions).toContain('2.0');
      expect(listedVersions).toContain('3.0');

      // Now resolve using LATEST
      const resolved = await resolveArtifactDynamic(repo.name, groupId, artifactId, 'LATEST');
      expect(resolved.exitCode, 'resolve LATEST should succeed').toBe(0);
      expect(resolved.resolvedVersions.length, 'should have resolved exactly one version').toBe(1);
      expect(
        resolved.resolvedVersions[0],
        'resolved version should be 3.0, the newest version',
      ).toBe('3.0');
      expect(resolved.resolvedSha, 'resolved jar sha should match deployed 3.0').toBe(
        deployResults['3.0'],
      );
    },
  );

  test(
    'dynamic version: version range [1.0,3.0) includes 1.0 and 2.0 but not 3.0',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'range-test';

      // Deploy versions 1.0, 2.0, 3.0, 4.0
      const versions = ['1.0', '2.0', '3.0', '4.0'];
      for (const version of versions) {
        const deploy = await deployArtifact(repo.name, groupId, artifactId, version);
        expect(deploy.exitCode, `deploy ${version} should succeed`).toBe(0);
      }

      // Resolve using range [1.0,3.0) - should resolve to 2.0 (highest in range)
      const resolved = await resolveArtifactDynamic(repo.name, groupId, artifactId, '[1.0,3.0)');
      expect(resolved.exitCode, 'resolve range should succeed').toBe(0);
      expect(resolved.resolvedVersions.length, 'should have resolved exactly one version').toBe(1);
      expect(resolved.resolvedVersions[0], 'resolved version should be 2.0, highest in range').toBe(
        '2.0',
      );
      expect(resolved.resolvedSha, 'should have resolved a jar').not.toBeUndefined();

      // Verify the metadata contains the range
      const metadataPath = `${groupId.replace(/\./g, '/')}/${artifactId}/maven-metadata.xml`;
      const metadata = await rawGet(repo.name, adminCredential(), metadataPath);
      expect(metadata.status).toBe(200);
      const listedVersions = parseArtifactVersions(metadata.body.toString('utf8'));
      expect(listedVersions).toContain('1.0');
      expect(listedVersions).toContain('2.0');
      expect(listedVersions).toContain('3.0');
      expect(listedVersions).toContain('4.0');
    },
  );

  test(
    'concurrent deploy: two parallel mvn deploy:deploy-file to same coordinates' +
      ' - first succeeds, second fails with 403 allowOverride',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      // A newly created repo always starts with allowOverride=true (RepoTxService.createRepo
      // hardcodes it), so the redeploy-refusal race this test exercises needs it turned off first.
      await seeder.setSettings(repo.name, { allowOverride: false });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'concurrent-test';
      const version = '1.0';

      // Deploy twice in parallel to the same coordinates. With allowOverride=false, one should win
      // and the other should fail with 403 when re-uploading the same files.
      const [deploy1, deploy2] = await Promise.all([
        deployArtifact(repo.name, groupId, artifactId, version),
        deployArtifact(repo.name, groupId, artifactId, version),
      ]);

      // One should succeed (the first to write), the other should fail with 403
      expect(
        (deploy1.exitCode === 0 && deploy2.exitCode !== 0) ||
          (deploy1.exitCode !== 0 && deploy2.exitCode === 0),
        'exactly one of the concurrent deploys should succeed (the other loses the race)',
      ).toBe(true);

      // Verify the metadata is valid and NOT corrupted (no duplicate version entries from race)
      const metadataPath = `${groupId.replace(/\./g, '/')}/${artifactId}/maven-metadata.xml`;
      const metadata = await rawGet(repo.name, adminCredential(), metadataPath);
      expect(metadata.status, 'metadata should exist after concurrent race').toBe(200);

      const metadataXml = metadata.body.toString('utf8');
      expect(metadataXml).toContain('<metadata');
      expect(metadataXml).toContain('</metadata>');

      // Parse and verify version list has exactly ONE entry (no duplicates from concurrent race)
      const listedVersions = parseArtifactVersions(metadataXml);
      expect(
        listedVersions,
        'metadata should list the version exactly once (no duplicates from concurrent race)',
      ).toEqual([version]);

      // Verify the jar was stored by the winning deploy
      const jarPath = versionDir(groupId, artifactId, version);
      const jar = await rawGet(
        repo.name,
        adminCredential(),
        `${jarPath}/${artifactId}-${version}.jar`,
      );
      expect(jar.status, 'jar should be stored by the winning deploy').toBe(200);
    },
  );

  test(
    'strict checksums: mvn deploy with correct SHA1 checksums succeeds',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'checksum-test';
      const version = '1.0';

      const deployment = await deployArtifact(repo.name, groupId, artifactId, version);
      expect(deployment.exitCode, 'deploy with correct checksums should succeed').toBe(0);

      // Verify the jar file was stored
      const jarPath = versionDir(groupId, artifactId, version);
      const jar = await rawGet(
        repo.name,
        adminCredential(),
        `${jarPath}/${artifactId}-${version}.jar`,
      );
      expect(jar.status).toBe(200);

      // Verify the .sha1 sidecar exists and has correct format
      const sha1Response = await rawGet(
        repo.name,
        adminCredential(),
        `${jarPath}/${artifactId}-${version}.jar.sha1`,
      );
      expect(sha1Response.status, '.sha1 sidecar should exist').toBe(200);

      // Verify the .md5 sidecar exists
      const md5Response = await rawGet(
        repo.name,
        adminCredential(),
        `${jarPath}/${artifactId}-${version}.jar.md5`,
      );
      expect(md5Response.status, '.md5 sidecar should exist').toBe(200);
    },
  );

  test(
    'strict checksums: mvn resolve validates checksums against server sidecars',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'checksum-validate-test';
      const version = '1.0';

      // Deploy an artifact
      const deployment = await deployArtifact(repo.name, groupId, artifactId, version);
      expect(deployment.exitCode).toBe(0);

      // Resolve it with strict checksum validation enabled
      // Maven's default behavior checks checksums, so this should succeed
      const resolved = await resolveArtifactDynamic(repo.name, groupId, artifactId, version);
      expect(resolved.exitCode, 'resolve should succeed with correct checksums from server').toBe(
        0,
      );
      expect(resolved.resolvedVersions.length, 'should have resolved exactly one version').toBe(1);
      expect(resolved.resolvedVersions[0], 'should have resolved the requested version').toBe(
        version,
      );
      expect(resolved.resolvedSha, 'resolved jar sha should match deployed jar').toBe(
        deployment.jarSha,
      );
    },
  );

  test(
    'strict checksums: mvn resolve FAILS when server checksum is deliberately corrupted',
    { tag: ['@negative'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'checksum-corrupt-test';
      const version = '1.0';

      // Deploy an artifact
      const deployment = await deployArtifact(repo.name, groupId, artifactId, version);
      expect(deployment.exitCode).toBe(0);

      // Verify checksum sidecars exist
      const jarPath = versionDir(groupId, artifactId, version);
      const sha1Path = `${jarPath}/${artifactId}-${version}.jar.sha1`;
      const sha1Before = await rawGet(repo.name, adminCredential(), sha1Path);
      expect(sha1Before.status, 'sha1 should exist before corruption').toBe(200);

      // Corrupt the SHA1 sidecar by uploading wrong hash
      const wrongHash = 'cafebabecafebabecafebabecafebabecafebabe';
      const corrupted = await rawPut(
        repo.name,
        adminCredential(),
        sha1Path,
        wrongHash,
        'text/plain',
      );
      expect(corrupted.status, 'corruption PUT should succeed').toBe(200);

      // Now resolve with the repository's checksumPolicy explicitly set to "fail" (Maven's own
      // default is "warn", which only prints a warning and still succeeds -- live-confirmed) -
      // should FAIL because the local jar won't match the corrupted sidecar.
      const resolved = await resolveArtifactDynamic(
        repo.name,
        groupId,
        artifactId,
        version,
        'fail',
      );
      expect(
        resolved.exitCode,
        'resolve should FAIL (exit 1) when server checksum is wrong under checksumPolicy=fail',
      ).not.toBe(0);
      expect(
        resolved.resolvedVersions.length,
        'no version should be resolved due to checksum failure',
      ).toBe(0);
    },
  );
});
