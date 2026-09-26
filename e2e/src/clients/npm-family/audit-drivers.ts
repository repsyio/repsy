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
 * How the audit commands of the npm-family clients print their report (RPS-1484), shared by the specs
 * that run a real `audit` against a scanner (`matrix/audit-scanner.spec.ts` on stored findings,
 * `matrix/audit-lookup-scanner.spec.ts` on the lookup of pairs the repository does not store, RPS-1613):
 * the flag of a threshold, and the advisories of a package in the client's own report format.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

import type { RunResult } from '../exec.js';
import { bunExec } from './bun-client.js';
import type { ClientCtx } from './client.js';
import { runNpm } from './npm-client.js';
import { runPnpm } from './pnpm-client.js';
import { execYarn } from './yarn-berry-client.js';

/** What every client's report says about the one advisory, whatever its format. */
export interface Advisory {
  severity: string;
  title: string;
  url: string;
  vulnerableVersions: string;
}

/** A client's audit at a threshold ("nothing below critical counts"), and how it prints its report. */
export interface AuditDriver {
  /** `<client> audit` with the severity threshold set to `critical`, in the client's own flag. */
  atCritical(ctx: ClientCtx): Promise<RunResult>;
  /** The advisories of `package` in the report `client.audit` printed. */
  advisories(stdout: string, packageName: string): Advisory[];
  /** Whether the report says the tree has no vulnerability at all. */
  isClean(stdout: string): boolean;
}

const NPM_ARGS = (ctx: ClientCtx): string[] => [
  '--userconfig',
  path.join(ctx.home, '.npmrc'),
  '--cache',
  path.join(ctx.home, 'npm-cache'),
];

export const DRIVERS: Record<string, AuditDriver> = {
  // npm 7+ report: `vulnerabilities.<name>.via[]` holds the advisories, `metadata.vulnerabilities` the counts.
  npm: {
    atCritical: (ctx) =>
      runNpm(ctx, 'npm-audit-critical', [
        'audit',
        '--json',
        '--audit-level=critical',
        ...NPM_ARGS(ctx),
      ]),
    advisories: (stdout, name) => {
      const report = JSON.parse(stdout) as {
        vulnerabilities: Record<
          string,
          { via: { title?: string; url?: string; severity: string; range: string }[] }
        >;
      };
      return (report.vulnerabilities[name]?.via ?? []).map((via) => ({
        severity: via.severity,
        title: via.title ?? '',
        url: via.url ?? '',
        vulnerableVersions: via.range,
      }));
    },
    isClean: (stdout) => {
      const report = JSON.parse(stdout) as { metadata: { vulnerabilities: { total: number } } };
      return report.metadata.vulnerabilities.total === 0;
    },
  },
  // pnpm answers the npm 6 report: `advisories.<id>` and per-severity counts.
  pnpm: {
    atCritical: (ctx) =>
      runPnpm(ctx, 'pnpm-audit-critical', ['audit', '--json', '--audit-level', 'critical']),
    advisories: (stdout, name) => {
      const report = JSON.parse(stdout) as {
        advisories: Record<
          string,
          {
            module_name: string;
            title: string;
            url: string;
            severity: string;
            vulnerable_versions: string;
          }
        >;
      };
      return Object.values(report.advisories)
        .filter((advisory) => advisory.module_name === name)
        .map((advisory) => ({
          severity: advisory.severity,
          title: advisory.title,
          url: advisory.url,
          vulnerableVersions: advisory.vulnerable_versions,
        }));
    },
    isClean: (stdout) =>
      Object.keys((JSON.parse(stdout) as { advisories: object }).advisories).length === 0,
  },
  // bun prints the bare map `{<name>: [advisory]}`, not npm's report.
  bun: {
    // Text mode: `--json --audit-level=critical` still lists the advisory and exits 1 (bun 1.3.14).
    atCritical: (ctx) => bunExec(ctx, 'bun-audit-critical', ['audit', '--audit-level=critical']),
    advisories: (stdout, name) => {
      const report = JSON.parse(stdout) as Record<
        string,
        { title: string; url: string; severity: string; vulnerable_versions: string }[]
      >;
      return (report[name] ?? []).map((advisory) => ({
        severity: advisory.severity,
        title: advisory.title,
        url: advisory.url,
        vulnerableVersions: advisory.vulnerable_versions,
      }));
    },
    isClean: (stdout) => Object.keys(JSON.parse(stdout) as object).length === 0,
  },
  // berry has no JSON report worth parsing here: its own tree of `Issue:` / `URL:` / ... lines.
  'yarn-berry': {
    atCritical: (ctx) =>
      execYarn(ctx, 'yarn4-audit-critical', ['npm', 'audit', '--severity', 'critical']),
    advisories: (stdout, name) => {
      const block = stdout.split(/^└─ |^├─ /m).find((part) => part.startsWith(name));
      if (!block) {
        return [];
      }
      const field = (label: string): string =>
        new RegExp(`${label}: (.*)`).exec(block)?.[1]?.trim() ?? '';
      return [
        {
          severity: field('Severity'),
          title: field('Issue'),
          url: field('URL'),
          vulnerableVersions: field('Vulnerable Versions'),
        },
      ];
    },
    isClean: (stdout) => stdout.includes('No audit suggestions'),
  },
};

/**
 * A consumer that has a lockfile and nothing installed, so that its audit asks about exactly the pairs
 * `dependencies` names, whether or not any registry holds them (npm and pnpm audit the lockfile; an
 * install would need every version to exist). `clientId` is `npm` or `pnpm`.
 */
export async function renderLockfileOnlyConsumer(
  clientId: string,
  dir: string,
  dependencies: Record<string, string>,
): Promise<void> {
  await fs.mkdir(dir, { recursive: true });
  const manifest = { name: 'audit-lockfile-only', version: '0.0.0', private: true, dependencies };
  await fs.writeFile(path.join(dir, 'package.json'), `${JSON.stringify(manifest, null, 2)}\n`);

  // Not a real digest: nothing is fetched, the client only reads the lockfile.
  const integrity = `sha512-${Buffer.alloc(64, 7).toString('base64')}`;
  if (clientId === 'npm') {
    const packages: Record<string, unknown> = {
      '': { name: manifest.name, version: manifest.version, dependencies },
    };
    for (const [name, version] of Object.entries(dependencies)) {
      packages[`node_modules/${name}`] = {
        version,
        resolved: `https://registry.npmjs.org/${name}/-/${name.split('/').pop()}-${version}.tgz`,
        integrity,
      };
    }
    const lock = {
      name: manifest.name,
      version: manifest.version,
      lockfileVersion: 3,
      requires: true,
      packages,
    };
    await fs.writeFile(path.join(dir, 'package-lock.json'), `${JSON.stringify(lock, null, 2)}\n`);
    return;
  }
  if (clientId === 'pnpm') {
    const entries = Object.entries(dependencies);
    const lines = [
      "lockfileVersion: '9.0'",
      '',
      'settings:',
      '  autoInstallPeers: true',
      '  excludeLinksFromLockfile: false',
      '',
      'importers:',
      '  .:',
      '    dependencies:',
      ...entries.flatMap(([name, version]) => [
        `      '${name}':`,
        `        specifier: ${version}`,
        `        version: ${version}`,
      ]),
      '',
      'packages:',
      ...entries.flatMap(([name, version]) => [
        '',
        `  '${name}@${version}':`,
        `    resolution: {integrity: ${integrity}}`,
      ]),
      '',
      'snapshots:',
      ...entries.flatMap(([name, version]) => ['', `  '${name}@${version}': {}`]),
      '',
    ];
    await fs.writeFile(path.join(dir, 'pnpm-lock.yaml'), lines.join('\n'));
    return;
  }
  throw new Error(`no lockfile-only consumer for ${clientId}`);
}
