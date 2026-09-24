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

/** Small helpers every per-protocol seeder shares. */
import type { RawResponse } from '../../clients/raw-http.js';
import type { PackageProtocol } from '../packages.js';

export const DEFAULT_VERSION = '1.0.0';

/** Throws unless `res` is one of `ok` (default 200/201/202), naming the step and the server's reply. */
export function expectPublished(
  res: Pick<RawResponse, 'status' | 'body' | 'msgId'>,
  what: string,
  ok: readonly number[] = [200, 201, 202],
): void {
  if (!ok.includes(res.status)) {
    const detail = res.body.toString('utf8').slice(0, 300);
    throw new Error(
      `package seed: ${what} answered ${res.status}${res.msgId ? ` (${res.msgId})` : ''}: ${detail}`,
    );
  }
}

/**
 * A default identity that is valid for `protocol` and unique per test and `index` (lower-case, so it
 * is also a legal docker image and PEP 503 name). Only the four seeded protocols are defined here;
 * RPS-1257 adds the rest next to their seeders.
 */
export function defaultPackageName(
  protocol: PackageProtocol,
  runId: string,
  index = 1,
  scoped = true,
): string {
  const base = `e2e-${runId}-pkg-${index}`;
  switch (protocol) {
    case 'maven':
      // One group per index, so deleting a GROUP from the list (that is what the group list's Delete
      // does) never takes a sibling package with it; pass explicit names to share one group.
      return `io.repsy.e2e.${runId.replace(/[^a-z0-9]/gi, '')}.g${index}:pkg-${index}`;
    case 'npm':
      return scoped ? `@e2e-${runId}/pkg-${index}` : base;
    case 'docker':
    case 'pypi':
      return base;
    default:
      throw new Error(
        `defaultPackageName: "${protocol}" has no default name yet (RPS-1257 adds it)`,
      );
  }
}
