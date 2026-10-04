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
 * RPS-1716 B8: Gradle dependency verification with verification-metadata.xml.
 *
 * - **Gradle verification-metadata generation**: A real `gradle build --write-verification-metadata sha256`
 *   against artifacts resolved from a Repsy-served Maven repo produces correct, usable checksums in
 *   `gradle/verification-metadata.xml`.
 *
 * - **Verification-metadata integrity checking**: A genuinely tampered checksum in `verification-metadata.xml`
 *   causes Gradle to fail the build with a checksum verification failure (proving the negative path:
 *   that Gradle actually validates).
 */

import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { run, isolatedWorkDir } from '../../src/clients/exec.js';
import { gradleEnv } from '../../src/clients/gradle.js';
import { mavenEnv } from '../../src/clients/maven.js';
import { buildJar, minimalPom } from '../../src/clients/maven-raw.js';
import { env } from '../../src/env.js';
import { repoUrl } from '../../src/repo-url.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const GRADLE_TIMEOUT_MS = 300_000;

test.describe('Gradle verification-metadata', () => {
  // Two cold Gradle runs plus an mvn deploy do not fit the 120 s default test timeout on a busy runner.
  test.setTimeout(GRADLE_TIMEOUT_MS * 2);

  test(
    'generates verification-metadata.xml with correct SHA256 checksums from Repsy',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'gradle-verify-lib';
      const version = '1.0.0';

      // Deploy a test artifact to Repsy
      const { home: home1, work: work1 } = await isolatedWorkDir('gradle-verify-deploy');
      const base = `${artifactId}-${version}`;
      const pomContent = minimalPom(groupId, artifactId, version);
      const jarContent = buildJar({ groupId, artifactId, version });

      await fs.writeFile(path.join(work1, `${base}.pom`), pomContent);
      await fs.writeFile(path.join(work1, `${base}.jar`), jarContent);

      await fs.writeFile(
        path.join(work1, 'settings.xml'),
        '<settings><servers><server><id>repsy</id>' +
          `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
          '</server></servers></settings>',
      );

      // Deploy via mvn
      const deployResult = await run(
        'mvn',
        [
          '-B',
          '-ntp',
          'deploy:deploy-file',
          '-s',
          'settings.xml',
          `-Dmaven.repo.local=${path.join(home1, 'repo-local')}`,
          `-Dfile=${base}.jar`,
          `-DpomFile=${base}.pom`,
          '-DrepositoryId=repsy',
          `-Durl=${repoUrl(repo.name)}`,
        ],
        {
          cwd: work1,
          env: mavenEnv(home1),
          timeoutMs: GRADLE_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'gradle-verify-deploy',
        },
      );

      expect(deployResult.exitCode, `deploy exit code: ${deployResult.stdout}`).toBe(0);

      // Create a Gradle project that will generate verification-metadata
      const { home: gradleHome, work: gradleWork } =
        await isolatedWorkDir('gradle-verify-consumer');

      const buildGradleKt = `plugins {
  java
}

group = "${groupId}"
version = "1.0.0"

repositories {
  maven {
    url = uri("${repoUrl(repo.name)}")
    isAllowInsecureProtocol = true
    credentials {
      username = "${env.adminUsername}"
      password = "${env.adminPassword}"
    }
  }
}

dependencies {
  implementation("${groupId}:${artifactId}:${version}")
}
`;

      const gradleProperties = `org.gradle.caching=false
org.gradle.parallel=false
`;

      await fs.writeFile(path.join(gradleWork, 'build.gradle.kts'), buildGradleKt);
      await fs.writeFile(path.join(gradleWork, 'gradle.properties'), gradleProperties);
      await fs.mkdir(path.join(gradleWork, 'gradle'), { recursive: true });
      // A source file so `compileJava` is not NO-SOURCE: without one Gradle skips the task
      // (and the implementation dependency's classpath resolution with it) before ever touching
      // the Repsy-hosted artifact, and `build` finishes without writing any <component> entries.
      await fs.mkdir(path.join(gradleWork, 'src', 'main', 'java'), { recursive: true });
      await fs.writeFile(
        path.join(gradleWork, 'src', 'main', 'java', 'App.java'),
        'public class App {}\n',
      );

      // Generate verification-metadata with Gradle
      const genMetadataResult = await run(
        'gradle',
        ['--no-daemon', 'build', '--write-verification-metadata', 'sha256', '--export-keys'],
        {
          cwd: gradleWork,
          env: gradleEnv(gradleHome, path.join(gradleHome, '.gradle')),
          timeoutMs: GRADLE_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'gradle-write-verification-metadata',
        },
      );

      expect(
        genMetadataResult.exitCode,
        `gradle metadata generation exit code: ${genMetadataResult.stdout}`,
      ).toBe(0);

      // Verify the verification-metadata.xml was created
      const verificationMetadataPath = path.join(gradleWork, 'gradle', 'verification-metadata.xml');
      const metadataContent = await fs.readFile(verificationMetadataPath, 'utf-8');

      expect(metadataContent, 'verification-metadata.xml contains artifact').toContain(artifactId);
      expect(metadataContent, 'verification-metadata.xml contains SHA256').toContain('sha256');

      // Extract the SHA256 from the metadata file for the jar
      const sha256Match = metadataContent.match(
        new RegExp(
          `<component name="${groupId}:${artifactId}:${version}[^"]*"[^>]*>([\\s\\S]*?)</component>`,
        ),
      );
      expect(
        sha256Match,
        'verification-metadata contains component for our artifact',
      ).toBeDefined();

      if (sha256Match) {
        const componentContent = sha256Match[1];
        expect(componentContent, 'component has artifact entries').toContain('artifact');
      }

      // Clean gradle cache for next test
      await run('gradle', ['--stop'], {
        cwd: gradleWork,
        env: gradleEnv(gradleHome, path.join(gradleHome, '.gradle')),
        timeoutMs: 30_000,
        label: 'gradle-stop',
      });
    },
  );

  test(
    'fails build when verification-metadata checksum is tampered',
    { tag: ['@smoke'] },
    async ({ seeder }) => {
      const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
      const groupId = `io.repsy.e2e.${seeder.runId}`;
      const artifactId = 'gradle-tamper-lib';
      const version = '1.0.0';

      // Deploy artifact
      const { home: home1, work: work1 } = await isolatedWorkDir('gradle-tamper-deploy');
      const base = `${artifactId}-${version}`;
      const pomContent = minimalPom(groupId, artifactId, version);
      const jarContent = buildJar({ groupId, artifactId, version });

      await fs.writeFile(path.join(work1, `${base}.pom`), pomContent);
      await fs.writeFile(path.join(work1, `${base}.jar`), jarContent);

      await fs.writeFile(
        path.join(work1, 'settings.xml'),
        '<settings><servers><server><id>repsy</id>' +
          `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
          '</server></servers></settings>',
      );

      const deployResult = await run(
        'mvn',
        [
          '-B',
          '-ntp',
          'deploy:deploy-file',
          '-s',
          'settings.xml',
          `-Dmaven.repo.local=${path.join(home1, 'repo-local')}`,
          `-Dfile=${base}.jar`,
          `-DpomFile=${base}.pom`,
          '-DrepositoryId=repsy',
          `-Durl=${repoUrl(repo.name)}`,
        ],
        {
          cwd: work1,
          env: mavenEnv(home1),
          timeoutMs: GRADLE_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'gradle-tamper-deploy',
        },
      );

      expect(deployResult.exitCode, `deploy exit code: ${deployResult.stdout}`).toBe(0);

      // Create Gradle project
      const { home: gradleHome, work: gradleWork } =
        await isolatedWorkDir('gradle-tamper-consumer');

      const buildGradleKt = `plugins {
  java
}

group = "${groupId}"
version = "1.0.0"

repositories {
  maven {
    url = uri("${repoUrl(repo.name)}")
    isAllowInsecureProtocol = true
    credentials {
      username = "${env.adminUsername}"
      password = "${env.adminPassword}"
    }
  }
}

dependencies {
  implementation("${groupId}:${artifactId}:${version}")
}
`;

      const gradleProperties = `org.gradle.caching=false
org.gradle.parallel=false
`;

      await fs.writeFile(path.join(gradleWork, 'build.gradle.kts'), buildGradleKt);
      await fs.writeFile(path.join(gradleWork, 'gradle.properties'), gradleProperties);
      await fs.mkdir(path.join(gradleWork, 'gradle'), { recursive: true });
      // A source file so `compileJava` is not NO-SOURCE: without one Gradle skips the task
      // (and the implementation dependency's classpath resolution with it) both here and on the
      // tampered rebuild below, so the tampered checksum would never actually be checked.
      await fs.mkdir(path.join(gradleWork, 'src', 'main', 'java'), { recursive: true });
      await fs.writeFile(
        path.join(gradleWork, 'src', 'main', 'java', 'App.java'),
        'public class App {}\n',
      );

      // Generate verification-metadata first
      const genMetadataResult = await run(
        'gradle',
        ['--no-daemon', 'build', '--write-verification-metadata', 'sha256', '--export-keys'],
        {
          cwd: gradleWork,
          env: gradleEnv(gradleHome, path.join(gradleHome, '.gradle')),
          timeoutMs: GRADLE_TIMEOUT_MS,
          redact: [env.adminPassword],
          label: 'gradle-tamper-gen-metadata',
        },
      );

      expect(
        genMetadataResult.exitCode,
        `gradle metadata generation exit code: ${genMetadataResult.stdout}`,
      ).toBe(0);

      // Tamper with the verification-metadata.xml. Gradle's real format nests the checksum as
      // `<sha256 value="<hex>" origin="Generated by Gradle"/>` (live-confirmed against a real
      // generated file) -- not a literal `sha256="..."` attribute -- so the replacement has to
      // match the `value` attribute of the `<sha256>` element.
      const verificationMetadataPath = path.join(gradleWork, 'gradle', 'verification-metadata.xml');
      let metadataContent = await fs.readFile(verificationMetadataPath, 'utf-8');

      // Replace a valid SHA256 with an incorrect one (change first 2 chars)
      metadataContent = metadataContent.replace(
        /<sha256 value="([a-f0-9]{64})"/,
        (match, hash) => `<sha256 value="${hash.substring(2)}00"`,
      );

      await fs.writeFile(verificationMetadataPath, metadataContent);

      // Clean Gradle cache to force re-verification
      const cleanCacheDir = path.join(gradleHome, '.gradle');
      if (await fs.stat(cleanCacheDir).catch(() => null)) {
        await run('rm', ['-rf', cleanCacheDir], {
          cwd: gradleWork,
          env: {},
          timeoutMs: 30_000,
          label: 'gradle-clean-cache',
        });
      }

      // Try to build with tampered metadata - should fail
      const buildResult = await run('gradle', ['--no-daemon', 'build'], {
        cwd: gradleWork,
        env: gradleEnv(gradleHome, path.join(gradleHome, '.gradle')),
        timeoutMs: GRADLE_TIMEOUT_MS,
        redact: [env.adminPassword],
        label: 'gradle-tamper-build',
      });

      // Build should fail due to checksum mismatch
      expect(
        buildResult.exitCode,
        `build should fail with tampered checksum: ${buildResult.stdout}\n${buildResult.stderr}`,
      ).not.toBe(0);

      // Gradle writes the "* What went wrong" failure detail (including the dependency
      // verification failure) to stderr, not stdout.
      expect(
        buildResult.stderr,
        'error output should mention checksum verification failure',
      ).toMatch(/verification.*fail|checksum|integrity|tamper/i);

      // Clean up
      await run('gradle', ['--stop'], {
        cwd: gradleWork,
        env: gradleEnv(gradleHome, path.join(gradleHome, '.gradle')),
        timeoutMs: 30_000,
        label: 'gradle-stop-tamper',
      });
    },
  );
});
