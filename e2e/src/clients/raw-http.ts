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
 * Raw-HTTP building blocks shared by every protocol's own raw-HTTP client (`maven-raw.ts`,
 * `npm-raw.ts`, ...), moved out of `maven-raw.ts` (step 3a, RPS-294) so a second protocol does not
 * have to depend on maven's module to get them: the envelope shape a Repsy error response answers
 * with, the admin credential, a Basic `Authorization` header builder, a sha256 helper, and the
 * shared 429-backoff-and-retry wrapper for a raw response. Protocol-specific path building
 * (maven's group/artifact/version layout, npm's packument/tarball paths, ...) stays in each
 * protocol's own `<protocol>-raw.ts`.
 */
import { createHash } from 'node:crypto';

import { env } from '../env.js';
import { withBackoff429 } from '../scenarios/remote-throttle.js';
import type { MaterializedCredential } from '../scenarios/world.js';

/** The harness's admin credential, for looking at a repo regardless of the scenario's credential. */
export function adminCredential(): MaterializedCredential {
  return {
    transport: 'basic',
    username: env.adminUsername,
    password: env.adminPassword,
    kind: 'password',
  };
}

/** An `Authorization` header for a Basic credential; none at all for `anonymous`. */
export function authHeader(credential: MaterializedCredential): Record<string, string> {
  if (credential.transport !== 'basic') {
    return {};
  }
  const basic = Buffer.from(`${credential.username ?? ''}:${credential.password ?? ''}`).toString(
    'base64',
  );
  return { Authorization: `Basic ${basic}` };
}

export function sha256Hex(bytes: Uint8Array | string): string {
  return createHash('sha256').update(bytes).digest('hex');
}

export interface RawResponse {
  status: number;
  /** The server's error `msgId` when the body is its JSON error envelope, else `undefined`. */
  msgId?: string;
  body: Buffer;
}

/** Reads a Repsy JSON error envelope's `msgId`, when the body is one; `undefined` otherwise. */
export function msgIdOf(body: Buffer): string | undefined {
  try {
    const parsed = JSON.parse(body.toString('utf8')) as { msgId?: unknown };
    return typeof parsed.msgId === 'string' ? parsed.msgId : undefined;
  } catch {
    return undefined;
  }
}

/** `withBackoff429` speaks in bare statuses; this keeps the whole response of the final attempt. */
export async function withBackoff429Response(
  attempt: () => Promise<RawResponse>,
): Promise<RawResponse> {
  let last: RawResponse | undefined;
  await withBackoff429(async () => {
    last = await attempt();
    return last.status;
  });
  return last as RawResponse;
}
