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
import http from 'node:http';
import https from 'node:https';

import { env } from '../env.js';
import { withBackoff429 } from '../scenarios/remote-throttle.js';
import type { MaterializedCredential } from '../scenarios/world.js';

/**
 * The credential of the account the harness runs as, for looking at a repo regardless of the
 * scenario's credential: the `admin` user on Repsy OS, the tenant owner on Repsy Cloud (RPS-1498).
 */
export function ownerCredential(): MaterializedCredential {
  return {
    transport: 'basic',
    username: env.adminUsername,
    password: env.adminPassword,
    kind: 'password',
  };
}

/** The former name of `ownerCredential`, kept so the callers need no change. */
export const adminCredential = ownerCredential;

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

/**
 * `{"errors":[{"code","message","detail"}]}` -- `OciErrorBodyAdvice`'s envelope (RPS-1039, "Docker
 * and Helm OCI endpoints"), distinct from Repsy's own `msgId` envelope `msgIdOf` reads. Moved here
 * (step 4b, RPS-294) from `docker-raw.ts` so the Helm OCI adapter can share it without importing
 * docker's own module; `docker-raw.ts` re-exports it so nothing there changes shape.
 * `undefined` when the body is not that shape (e.g. a bodyless 401/400).
 */
export function ociErrorOf(
  body: Buffer,
): { code: string; message: string; detail?: unknown } | undefined {
  try {
    const parsed = JSON.parse(body.toString('utf8')) as {
      errors?: { code?: unknown; message?: unknown; detail?: unknown }[];
    };
    const first = parsed.errors?.[0];
    if (!first || typeof first.code !== 'string' || typeof first.message !== 'string') {
      return undefined;
    }
    return { code: first.code, message: first.message, detail: first.detail };
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

/**
 * A request whose body the server may refuse BEFORE it has read it (a size limit read off
 * `Content-Length`, RPS-1608), with the client's half of that race handled: the response is read while
 * the body is still being written, and a reset that follows a response already received is ignored.
 *
 * Why `fetch` cannot be used for this. The server answers `400` and closes the connection; Tomcat
 * swallows at most 2 MiB (`maxSwallowSize`) of the unread body first, so with a 10 MiB body it closes
 * with data still unread and the kernel answers the client's remaining writes with a reset (`write
 * EPIPE`, seen in 996 of 1000 sends). undici then fails the whole call (`TypeError: fetch failed` /
 * `terminated`) even though the response has arrived: 7 in 1000 on a loaded runner. Here the `response`
 * event resolves the promise, and the `error` that comes after it is the end of the connection, not a
 * failed request. A reset that comes before any response still rejects: that would be a lost answer
 * (none in 3000 sends), and the test should say so instead of guessing.
 */
export function sendReadingResponse(
  url: string,
  init: { method: string; headers: Record<string, string>; body: Uint8Array | string },
): Promise<RawResponse> {
  const body = Buffer.from(init.body);
  const { method, headers } = init;
  const target = new URL(url);
  const transport = target.protocol === 'https:' ? https : http;
  return new Promise<RawResponse>((resolve, reject) => {
    let answered = false;
    const req = transport.request(
      target,
      { method, headers: { ...headers, 'Content-Length': String(body.length) } },
      (res) => {
        answered = true;
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        const done = (): void => {
          const bytes = Buffer.concat(chunks);
          resolve({ status: res.statusCode ?? 0, msgId: msgIdOf(bytes), body: bytes });
        };
        res.on('end', done);
        // The reset that closes the connection after the response can cut the body short: what
        // arrived is what the server sent before it hung up, and the caller asserts on it.
        res.on('error', done);
        res.on('close', done);
      },
    );
    req.on('error', (e) => {
      if (!answered) {
        reject(e);
      }
    });
    req.end(body);
  });
}
