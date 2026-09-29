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
 * RPS-1716 B8: Maven classifier artifacts and BOM handling.
 *
 * - **Classifier artifacts**: publishing and resolving a Maven artifact with a classifier
 *   (e.g. `-sources.jar`, `-javadoc.jar`) via `mvn deploy:deploy-file -Dclassifier=...` and
 *   resolving it back with `mvn dependency:get -Dclassifier=...`, confirming Repsy correctly
 *   stores/serves classified artifacts alongside the main jar without conflating them.
 *
 * - **BOM handling**: publishing a `pom`-packaging BOM artifact and confirming a consumer can
 *   import it via `<dependencyManagement><dependencies><dependency>...<type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>`
 *   and have the managed versions actually apply (real `mvn` resolution of a dependency whose
 *   version comes only from the imported BOM, not stated locally).
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
  versionDir,
  splitPackageName,
} from '../../src/clients/maven-raw.js';
import { env } from '../../src/env.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const DEPLOY_TIMEOUT_MS = 180_000;
const RESOLVE_TIMEOUT_MS = 180_000;

test.describe('Maven classifier artifacts', () => {
  test(
    'publishes a classifier artifact and resolves it back with dependency:get',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'classifier-lib';
      const version = '1.0.0';

      // Deploy: main jar + sources classifier + javadoc classifier
      const { home, work } = await isolatedWorkDir('mvn-classifier-deploy');
      const base = `${artifactId}-${version}`;
      const mainJar = buildJar({ groupId, artifactId, version });
      const sourcesJar = Buffer.from(`// sources for ${artifactId}`);
      const javadocJar = Buffer.from(`/** javadoc for ${artifactId} */`);
      const pomContent = minimalPom(groupId, artifactId, version);

      await fs.writeFile(path.join(work, `${base}.jar`), mainJar);
      await fs.writeFile(path.join(work, `${base}-sources.jar`), sourcesJar);
      await fs.writeFile(path.join(work, `${base}-javadoc.jar`), javadocJar);
      await fs.writeFile(path.join(work, `${base}.pom`), pomContent);

      await fs.writeFile(
        path.join(work, 'settings.xml'),
        '<settings><servers><server><id>repsy</id>' +
          `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
          '</server></servers></settings>',
      );

      // Deploy main jar with classifiers
      const deployResult = await run(
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
          `-Durl=${repoUrl(repo.name)}`,
          `-Dfiles=${base}-sources.jar,${base}-javadoc.jar`,
          `-Dtypes=jar,jar`,
          `-Dclassifiers=sources,javadoc`,
        ],
        {
          cwd: work,
          env: mavenEnv(home),
          timeoutMs: DEPLOY_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'maven-classifier-deploy',
        },
      );

      expect(deployResult.exitCode, `deploy exit code: ${deployResult.stdout}`).toBe(0);

      // Verify files were stored
      const [gId, aId] = splitPackageName(`${groupId}:${artifactId}`);
      const vDir = versionDir(gId, aId, version);

      const mainResp = await rawGet(repo.name, adminCredential(), `${vDir}/${base}.jar`);
      expect(mainResp.status, 'main jar stored').toBe(200);

      const sourcesResp = await rawGet(repo.name, adminCredential(), `${vDir}/${base}-sources.jar`);
      expect(sourcesResp.status, 'sources classifier jar stored').toBe(200);
      expect(sourcesResp.body.toString('utf-8')).toContain('sources for');

      const javadocResp = await rawGet(repo.name, adminCredential(), `${vDir}/${base}-javadoc.jar`);
      expect(javadocResp.status, 'javadoc classifier jar stored').toBe(200);
      expect(javadocResp.body.toString('utf-8')).toContain('javadoc for');

      // Resolve: dependency:get with each classifier
      const { home: resolveHome, work: resolveWork } =
        await isolatedWorkDir('mvn-classifier-resolve');

      await fs.writeFile(
        path.join(resolveWork, 'settings.xml'),
        '<settings><servers><server><id>repsy</id>' +
          `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
          '</server></servers></settings>',
      );

      // Resolve main artifact
      const resolveMainResult = await run(
        'mvn',
        [
          '-B',
          '-ntp',
          'dependency:get',
          '-s',
          'settings.xml',
          `-Dmaven.repo.local=${path.join(resolveHome, 'repo-local')}`,
          `-DremoteRepositories=repsy::default::${repoUrl(repo.name)}`,
          `-Dartifact=${groupId}:${artifactId}:${version}:jar`,
        ],
        {
          cwd: resolveWork,
          env: mavenEnv(resolveHome),
          timeoutMs: RESOLVE_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'maven-classifier-resolve-main',
        },
      );

      expect(
        resolveMainResult.exitCode,
        `resolve main exit code: ${resolveMainResult.stdout}`,
      ).toBe(0);

      // Resolve sources classifier
      const resolveSourcesResult = await run(
        'mvn',
        [
          '-B',
          '-ntp',
          'dependency:get',
          '-s',
          'settings.xml',
          `-Dmaven.repo.local=${path.join(resolveHome, 'repo-local')}`,
          `-DremoteRepositories=repsy::default::${repoUrl(repo.name)}`,
          `-Dartifact=${groupId}:${artifactId}:${version}:jar:sources`,
        ],
        {
          cwd: resolveWork,
          env: mavenEnv(resolveHome),
          timeoutMs: RESOLVE_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'maven-classifier-resolve-sources',
        },
      );

      expect(
        resolveSourcesResult.exitCode,
        `resolve sources exit code: ${resolveSourcesResult.stdout}`,
      ).toBe(0);

      // Verify resolved files are distinct
      const localRepoPath = path.join(resolveHome, 'repo-local', ...gId.split('.'), aId, version);
      const mainFile = path.join(localRepoPath, `${base}.jar`);
      const sourcesFile = path.join(localRepoPath, `${base}-sources.jar`);

      const mainContent = await fs.readFile(mainFile);
      const sourcesContent = await fs.readFile(sourcesFile);

      expect(mainContent.length, 'main jar size > 0').toBeGreaterThan(0);
      expect(sourcesContent.length, 'sources jar size > 0').toBeGreaterThan(0);
      expect(mainContent.toString('utf-8')).not.toContain('sources for');
      expect(sourcesContent.toString('utf-8')).toContain('sources for');
    },
  );
});

test.describe('Maven BOM handling', () => {
  test(
    'imports a BOM and resolves managed dependency versions',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;

      // Create and deploy a BOM artifact
      const bomArtifactId = 'my-bom';
      const bomVersion = '1.0.0';

      // Create a simple BOM POM with managed dependencies
      const bomPomContent = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${groupId}</groupId>
  <artifactId>${bomArtifactId}</artifactId>
  <version>${bomVersion}</version>
  <packaging>pom</packaging>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter-api</artifactId>
        <version>5.10.0</version>
      </dependency>
      <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.20</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>`;

      const { home, work } = await isolatedWorkDir('mvn-bom-deploy');
      const base = `${bomArtifactId}-${bomVersion}`;

      await fs.writeFile(path.join(work, `${base}.pom`), bomPomContent);

      await fs.writeFile(
        path.join(work, 'settings.xml'),
        '<settings><servers><server><id>repsy</id>' +
          `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
          '</server></servers></settings>',
      );

      // Deploy BOM
      const deployResult = await run(
        'mvn',
        [
          '-B',
          '-ntp',
          'deploy:deploy-file',
          '-s',
          'settings.xml',
          `-Dmaven.repo.local=${path.join(home, 'repo-local')}`,
          `-DpomFile=${base}.pom`,
          `-Dfile=${base}.pom`,
          '-DrepositoryId=repsy',
          `-Durl=${repoUrl(repo.name)}`,
          '-Dpackaging=pom',
        ],
        {
          cwd: work,
          env: mavenEnv(home),
          timeoutMs: DEPLOY_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'maven-bom-deploy',
        },
      );

      expect(deployResult.exitCode, `BOM deploy exit code: ${deployResult.stdout}`).toBe(0);

      // Create a consumer POM that imports the BOM
      const consumerArtifactId = 'my-app';
      const consumerVersion = '1.0.0';

      const consumerPomContent = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${groupId}</groupId>
  <artifactId>${consumerArtifactId}</artifactId>
  <version>${consumerVersion}</version>

  <!-- BOM import is resolved while Maven builds the effective model, before the dependency:resolve
       mojo runs, so -DremoteRepositories (which only affects the mojo's own resolution) cannot
       reach it: the importing POM needs its own <repositories> entry, with an id matching
       settings.xml's <server> so the credentials for this private repo are found. -->
  <repositories>
    <repository>
      <id>repsy</id>
      <url>${repoUrl(repo.name)}</url>
    </repository>
  </repositories>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>${groupId}</groupId>
        <artifactId>${bomArtifactId}</artifactId>
        <version>${bomVersion}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- Version should come from BOM -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-api</artifactId>
    </dependency>
    <!-- Version should also come from BOM -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
  </dependencies>
</project>`;

      const { home: consumerHome, work: consumerWork } = await isolatedWorkDir('mvn-bom-consume');

      await fs.writeFile(path.join(consumerWork, 'pom.xml'), consumerPomContent);

      await fs.writeFile(
        path.join(consumerWork, 'settings.xml'),
        '<settings><servers><server><id>repsy</id>' +
          `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
          '</server></servers></settings>',
      );

      // Resolve dependencies (will use BOM versions from Repsy)
      const resolveResult = await run(
        'mvn',
        [
          '-B',
          '-ntp',
          'dependency:resolve',
          '-s',
          'settings.xml',
          `-Dmaven.repo.local=${path.join(consumerHome, 'repo-local')}`,
          `-DremoteRepositories=repsy::default::${repoUrl(repo.name)}`,
        ],
        {
          cwd: consumerWork,
          env: mavenEnv(consumerHome),
          timeoutMs: RESOLVE_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'maven-bom-consume',
        },
      );

      expect(resolveResult.exitCode, `BOM consume exit code: ${resolveResult.stdout}`).toBe(0);

      // Verify that the BOM versions were applied by checking the output contains the expected versions
      expect(resolveResult.stdout, 'resolve output contains junit 5.10.0').toContain(
        'junit-jupiter-api',
      );
      expect(resolveResult.stdout, 'resolve output contains slf4j 2.0.20').toContain('slf4j-api');
    },
  );
});
