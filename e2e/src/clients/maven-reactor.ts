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

/**
 * A real multi-module Maven reactor and a real WAR, deployed and consumed by the real `mvn`
 * (RPS-1488, recovered from the step-5 branch `rps-294-e2e-maven-specific-a`).
 *
 * `deployReactor` renders a parent POM (`packaging=pom`) with a jar module `lib` and a war module `web`
 * (which depends on `lib`) and runs ONE `mvn deploy` from its root. `deployWar` deploys a standalone
 * hand-built war. `consume` is a second project that depends on the jar and the war (and, through them,
 * on the parent POM they name) and resolves them into a clean local repository, so everything it gets
 * came from the server. Every invocation has a fresh primary local repository and the shared read-only
 * tail, like every other `mvn` of this harness (`clients/maven.ts`).
 */
import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

import { env } from '../env.js';
import type { MaterializedCredential } from '../scenarios/world.js';
import { isolatedWorkDir, run, type RunResult } from './exec.js';
import {
  credentialView,
  ensureSharedCacheWarm,
  mavenEnv,
  renderTemplate,
  SHARED_M2_DIR,
} from './maven.js';
import { groupPath, sha256Hex } from './maven-raw.js';

const DEPLOY_TIMEOUT_MS = 240_000;
const CONSUME_TIMEOUT_MS = 180_000;

export interface ReactorLayout {
  groupId: string;
  version: string;
  parentArtifactId: string;
  libArtifactId: string;
  webArtifactId: string;
}

export interface DeployedReactor {
  result: RunResult;
  /** sha256 of the jar and the war the reactor build produced. */
  libSha256: string;
  webSha256: string;
}

export interface Consumed {
  result: RunResult;
  /** The clean local repository the consumer resolved into. */
  localRepo: string;
  /** Where `dependency:copy-dependencies` put what it resolved. */
  copied: string;
}

async function mvn(
  args: readonly string[],
  cwd: string,
  home: string,
  localRepo: string,
  credential: MaterializedCredential,
  label: string,
  timeoutMs: number,
): Promise<RunResult> {
  return run(
    'mvn',
    [
      '-B',
      '-ntp',
      ...args,
      '-s',
      'settings.xml',
      `-Dmaven.repo.local=${localRepo}`,
      `-Dmaven.repo.local.tail=${SHARED_M2_DIR}`,
    ],
    {
      cwd,
      env: mavenEnv(home),
      timeoutMs,
      redact: credential.password ? [credential.password] : [],
      label,
    },
  );
}

async function renderSettings(work: string, credential: MaterializedCredential): Promise<void> {
  await renderTemplate(
    'settings.template.xml',
    path.join(work, 'settings.xml'),
    credentialView(credential),
  );
}

async function renderWebXml(warDir: string, artifactId: string): Promise<void> {
  const webInf = path.join(warDir, 'src', 'main', 'webapp', 'WEB-INF');
  await fs.mkdir(webInf, { recursive: true });
  await renderTemplate('web.template.xml', path.join(webInf, 'web.xml'), { artifactId });
}

/** One `mvn deploy` of the reactor root: the parent POM, the jar `lib` and the war `web`. */
export async function deployReactor(
  repoName: string,
  credential: MaterializedCredential,
  layout: ReactorLayout,
): Promise<DeployedReactor> {
  await ensureSharedCacheWarm();
  const { home, work } = await isolatedWorkDir('mvn-reactor-deploy');
  const repoUrl = `${env.repoBaseUrl}/${repoName}`;
  const { groupId, version, parentArtifactId } = layout;

  await renderTemplate('pom.parent.template.xml', path.join(work, 'pom.xml'), {
    groupId,
    artifactId: parentArtifactId,
    version,
    repoUrl,
  });

  const libDir = path.join(work, 'lib');
  await fs.mkdir(path.join(libDir, 'src', 'main', 'resources'), { recursive: true });
  await renderTemplate('pom.module.template.xml', path.join(libDir, 'pom.xml'), {
    groupId,
    parentArtifactId,
    artifactId: layout.libArtifactId,
    version,
  });
  // A random marker resource makes the jar's bytes unique to this deploy (as `clients/maven.ts` does).
  await fs.writeFile(
    path.join(libDir, 'src', 'main', 'resources', 'e2e-deploy-marker.txt'),
    randomUUID(),
    'utf8',
  );

  const webDir = path.join(work, 'web');
  await fs.mkdir(webDir, { recursive: true });
  await renderTemplate('pom.war.template.xml', path.join(webDir, 'pom.xml'), {
    groupId,
    parentArtifactId,
    artifactId: layout.webArtifactId,
    libArtifactId: layout.libArtifactId,
    version,
  });
  await renderWebXml(webDir, layout.webArtifactId);
  await renderSettings(work, credential);

  const result = await mvn(
    ['deploy'],
    work,
    home,
    path.join(home, 'repo-local'),
    credential,
    'maven-reactor-deploy',
    DEPLOY_TIMEOUT_MS,
  );

  const built = async (dir: string, artifactId: string, extension: string) =>
    sha256Hex(
      await fs
        .readFile(path.join(dir, 'target', `${artifactId}-${version}.${extension}`))
        .catch(() => Buffer.alloc(0)),
    );
  return {
    result,
    libSha256: await built(libDir, layout.libArtifactId, 'jar'),
    webSha256: await built(webDir, layout.webArtifactId, 'war'),
  };
}

/** A standalone, hand-built `packaging=war` project deployed by a real `mvn deploy`. */
export async function deployWar(
  repoName: string,
  credential: MaterializedCredential,
  opts: { groupId: string; artifactId: string; version: string },
): Promise<{ result: RunResult; sha256: string }> {
  await ensureSharedCacheWarm();
  const { home, work } = await isolatedWorkDir('mvn-war-deploy');
  await renderTemplate('pom.war.template.xml', path.join(work, 'pom.xml'), {
    ...opts,
    repoUrl: `${env.repoBaseUrl}/${repoName}`,
  });
  await renderWebXml(work, opts.artifactId);
  await renderSettings(work, credential);

  const result = await mvn(
    ['deploy'],
    work,
    home,
    path.join(home, 'repo-local'),
    credential,
    'maven-war-deploy',
    DEPLOY_TIMEOUT_MS,
  );
  const war = await fs
    .readFile(path.join(work, 'target', `${opts.artifactId}-${opts.version}.war`))
    .catch(() => Buffer.alloc(0));
  return { result, sha256: sha256Hex(war) };
}

/**
 * A second project that depends on the reactor's jar and war: `dependency:copy-dependencies` in a
 * clean local repository. What it copies is what Maven resolved from the server.
 */
export async function consume(
  repoName: string,
  credential: MaterializedCredential,
  layout: Omit<ReactorLayout, 'parentArtifactId' | 'libArtifactId'> & { libArtifactId?: string },
): Promise<Consumed> {
  await ensureSharedCacheWarm();
  const { home, work } = await isolatedWorkDir('mvn-reactor-consume');
  await renderTemplate('pom.consumer.template.xml', path.join(work, 'pom.xml'), {
    groupId: layout.groupId,
    libArtifactId: layout.libArtifactId,
    webArtifactId: layout.webArtifactId,
    version: layout.version,
    repoUrl: `${env.repoBaseUrl}/${repoName}`,
  });
  await renderSettings(work, credential);

  const localRepo = path.join(home, 'repo-local');
  const copied = path.join(work, 'copied');
  const result = await mvn(
    ['dependency:copy-dependencies', `-DoutputDirectory=${copied}`],
    work,
    home,
    localRepo,
    credential,
    'maven-reactor-consume',
    CONSUME_TIMEOUT_MS,
  );
  return { result, localRepo, copied };
}

/** The file `mvn` left in a local repository for `groupId:artifactId:version` with `extension`. */
export function localRepoFile(
  localRepo: string,
  groupId: string,
  artifactId: string,
  version: string,
  extension: string,
): string {
  return path.join(
    localRepo,
    groupPath(groupId),
    artifactId,
    version,
    `${artifactId}-${version}.${extension}`,
  );
}
