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
 * A REAL `mvn deploy:deploy-file` of a signed release to a repo that verifies every signature
 * (`pgpVerifyAllSignaturesEnabled`, RPS-1188), with Maven's DEFAULT parallel upload (Maven Resolver's
 * `aether.connector.basic.threads`, 5), and the same deploy with one thread as the sequential
 * control. maven-gpg-plugin attaches the `.asc` of the POM, the main jar and every attached artifact
 * to the project, so `mvn deploy` hands Resolver all of them at once and uploads them in parallel: a
 * signature routinely overtakes the large file it signs (here a 4 MB javadoc jar). `deploy-file`
 * takes the same list of artifacts from its `-Dfiles`/`-Dclassifiers`/`-Dtypes`, so the run below
 * uploads exactly what a signed `mvn deploy` does, with the signatures made in-process by OpenPGP.js
 * (`src/clients/pgp.ts`): this spec's key never touches a `gpg` binary.
 *
 * Before deferred verification such a deploy could fail with `404 itemNotFound` on the signature
 * that overtook its file. Now a signature that arrives first is parked, and verified when its file
 * arrives, so the deploy always ends complete: exit 0, every `.asc` stored byte-equal, the version
 * `signed`. `gpg-signed-deploy.spec.ts` is the counterpart with a real `gpg` key, `maven-gpg-plugin` and
 * Gradle's `signing` plugin.
 */
import { randomBytes } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { RepoType } from '../../src/api/panel-api.js';
import { run, isolatedWorkDir } from '../../src/clients/exec.js';
import {
  adminCredential,
  buildJar,
  minimalPom,
  rawGet,
  versionDir,
} from '../../src/clients/maven-raw.js';
import { detachedSign, generateKeyPair } from '../../src/clients/pgp.js';
import { env } from '../../src/env.js';
import { expect, test } from '../../src/scenarios/fixtures.js';

const ARTIFACT_ID = 'signed-lib';
const VERSION = '1.0';
const JAVADOC_BYTES = 4 * 1024 * 1024;
const DEPLOY_TIMEOUT_MS = 180_000;

interface Deploy {
  exitCode: number;
  stdout: string;
  files: Record<string, Buffer>;
  groupId: string;
  repoName: string;
}

/** Deploys a signed release in one `deploy:deploy-file` run, with the given Resolver thread count. */
async function signedDeploy(
  seeder: import('../../src/seed/seeder.js').Seeder,
  threads: number | undefined,
): Promise<Deploy> {
  const repo = await seeder.createRepo(RepoType.MAVEN, { privateRepo: true });
  await seeder.setSettings(repo.name, { pgpVerifyAllSignaturesEnabled: true });
  const key = await generateKeyPair();
  await seeder.registerPgpPublicKey(repo.name, key.publicKeyArmored);

  const groupId = `io.repsy.e2e.${seeder.runId}`;
  const { home, work } = await isolatedWorkDir('mvn-signed');
  const base = `${ARTIFACT_ID}-${VERSION}`;
  const contents: Record<string, Buffer> = {
    [`${base}.pom`]: Buffer.from(minimalPom(groupId, ARTIFACT_ID, VERSION)),
    [`${base}.jar`]: buildJar({ groupId, artifactId: ARTIFACT_ID, version: VERSION }),
    [`${base}-javadoc.jar`]: randomBytes(JAVADOC_BYTES),
    [`${base}-sources.jar`]: Buffer.from(`sources of ${repo.name}`),
  };
  for (const name of Object.keys(contents)) {
    contents[`${name}.asc`] = Buffer.from(
      await detachedSign(key.privateKeyArmored, contents[name]),
    );
  }
  for (const [name, bytes] of Object.entries(contents)) {
    await fs.writeFile(path.join(work, name), bytes);
  }

  await fs.writeFile(
    path.join(work, 'settings.xml'),
    '<settings><servers><server><id>repsy</id>' +
      `<username>${env.adminUsername}</username><password>${env.adminPassword}</password>` +
      '</server></servers></settings>',
  );

  // The unclassified attachments (the signatures of the POM and the main jar) come first: the
  // plugin reads the three lists positionally, and only its leading empty classifiers survive.
  const attached = [
    { file: `${base}.pom.asc`, type: 'pom.asc', classifier: '' },
    { file: `${base}.jar.asc`, type: 'jar.asc', classifier: '' },
    { file: `${base}-javadoc.jar`, type: 'jar', classifier: 'javadoc' },
    { file: `${base}-javadoc.jar.asc`, type: 'jar.asc', classifier: 'javadoc' },
    { file: `${base}-sources.jar`, type: 'jar', classifier: 'sources' },
    { file: `${base}-sources.jar.asc`, type: 'jar.asc', classifier: 'sources' },
  ];

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
      `-Durl=${env.repoBaseUrl}/${repo.name}`,
      `-Dfiles=${attached.map((a) => a.file).join(',')}`,
      `-Dtypes=${attached.map((a) => a.type).join(',')}`,
      `-Dclassifiers=${attached.map((a) => a.classifier).join(',')}`,
      ...(threads === undefined ? [] : [`-Daether.connector.basic.threads=${threads}`]),
    ],
    {
      cwd: work,
      env: { ...process.env, HOME: home },
      timeoutMs: DEPLOY_TIMEOUT_MS,
      redact: [env.adminPassword],
      label: `maven-signed-deploy-${threads ?? 'default'}`,
    },
  );

  return {
    exitCode: result.exitCode,
    stdout: result.stdout,
    files: contents,
    groupId,
    repoName: repo.name,
  };
}

/** What is wrong with a finished deploy: nothing, when it ended complete and signed. */
async function problemsOf(
  deploy: Deploy,
  panelApi: import('../../src/api/panel-api.js').PanelApi,
): Promise<string[]> {
  if (deploy.exitCode !== 0) {
    return [`mvn exited ${deploy.exitCode}\n${deploy.stdout.slice(-2000)}`];
  }

  const problems: string[] = [];
  const dir = versionDir(deploy.groupId, ARTIFACT_ID, VERSION);
  for (const [name, bytes] of Object.entries(deploy.files)) {
    const stored = await rawGet(deploy.repoName, adminCredential(), `${dir}/${name}`);
    if (stored.status !== 200) {
      problems.push(`GET ${name} answered ${stored.status}`);
    } else if (!stored.body.equals(bytes)) {
      problems.push(`${name} was stored with other bytes`);
    }
  }

  const version = await panelApi.getMavenArtifactVersion(
    deploy.repoName,
    deploy.groupId,
    ARTIFACT_ID,
    VERSION,
  );
  if (!version.signed) {
    problems.push('the version is not signed although every file has a verified signature');
  }

  return problems;
}

test.describe('maven signed deploy with a real client (RPS-1188)', () => {
  test(
    'the default parallel upload of a signed release with a 4 MB javadoc jar ends signed',
    { tag: ['@settings'] },
    async ({ seeder, panelApi }) => {
      expect(await problemsOf(await signedDeploy(seeder, undefined), panelApi)).toEqual([]);
    },
  );

  test(
    'the same deploy repeated with the default parallel upload ends signed every time',
    { tag: ['@settings'] },
    async ({ seeder, panelApi }) => {
      for (let attempt = 0; attempt < 3; attempt += 1) {
        expect(await problemsOf(await signedDeploy(seeder, undefined), panelApi)).toEqual([]);
      }
    },
  );

  test(
    'the sequential control (one connector thread) ends signed too',
    { tag: ['@settings'] },
    async ({ seeder, panelApi }) => {
      expect(await problemsOf(await signedDeploy(seeder, 1), panelApi)).toEqual([]);
    },
  );
});
