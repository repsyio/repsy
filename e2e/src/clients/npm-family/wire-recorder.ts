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
 * A wire recorder for the npm-family suite (RPS-1330): an in-process HTTP reverse proxy on
 * `127.0.0.1:<ephemeral>` in front of the registry under test, which records every request's method,
 * path, the headers a client's behaviour turns on (`Accept`, `Authorization`, `User-Agent`,
 * `npm-command`, conditional-request headers) and the response's status and caching headers, then
 * forwards the request untouched. It pins the facts a matrix cell depends on -- which `Accept` a
 * client asks with, which credential scheme it sends where, and that a credential never leaves the
 * repository it belongs to -- without trusting anyone's memory of a client's source.
 *
 * Structure follows `clients/golang-tls-shim.ts` (a per-test server the client is pointed at, `trace`
 * entries), minus TLS. A client is pointed at it through `RegistryBinding.baseUrl`. Two things to
 * know:
 *  - `dist.tarball` is the REGISTRY's address (RPS-1333: `REPO_BASE_URL`, never the address a
 *    request came in by), so a client would fetch tarballs around the recorder. With
 *    `rewriteTarballUrls` the recorder rewrites that address to its own in a JSON response, so the
 *    tarball GETs are recorded too. That changes the bytes a client sees (and so a lockfile's
 *    `resolved`), so it is off by default and only meant for a trace.
 *  - It is a per-test resource: `start()` in the test, `stop()` in a `finally`.
 */
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import zlib from 'node:zlib';

import { env } from '../../env.js';

export interface WireEntry {
  method: string;
  /** The request path and query, as the client sent it. */
  path: string;
  /** `Authorization` verbatim; only ever kept in memory for the test to compare. */
  authorization?: string;
  /** `Bearer` or `Basic`, when an `Authorization` header was sent. */
  authScheme?: string;
  accept?: string;
  userAgent?: string;
  npmCommand?: string;
  npmSession?: string;
  ifNoneMatch?: string;
  ifModifiedSince?: string;
  /** The request body as text, only when `captureRequestBody` is on (a publish's JSON document). */
  requestBody?: string;
  status: number;
  responseEtag?: string;
  responseLastModified?: string;
  responseVary?: string;
  responseCacheControl?: string;
  responseContentEncoding?: string;
}

export interface WireRecorder {
  /** `http://127.0.0.1:<port>`: the address a client is configured with. */
  readonly baseUrl: string;
  readonly entries: readonly WireEntry[];
  /** The entries whose path starts with `prefix` (a repository, `/<repo>/`). */
  under(prefix: string): WireEntry[];
  stop(): Promise<void>;
}

export interface WireRecorderOptions {
  /** Rewrite the registry's own address to the recorder's in JSON responses (see the header). */
  rewriteTarballUrls?: boolean;
  /**
   * Request headers to set on what is FORWARDED (the recorded entry keeps what the client sent): e.g.
   * `accept: 'application/json'` makes the registry answer the full packument to a client that asked
   * for the abbreviated one, to tell what a client does with each.
   */
  forwardHeaders?: Record<string, string>;
  /** Keep each request's body in `WireEntry.requestBody` (what a client PUT, e.g. a publish document). */
  captureRequestBody?: boolean;
}

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export async function startWireRecorder(options: WireRecorderOptions = {}): Promise<WireRecorder> {
  const upstream = new URL(env.repoBaseUrl);
  const entries: WireEntry[] = [];
  let ownBase = '';

  const server = http.createServer((req, res) => {
    const authorization = first(req.headers.authorization);
    const entry: WireEntry = {
      method: req.method ?? '',
      path: req.url ?? '',
      authorization,
      authScheme: authorization?.split(' ')[0],
      accept: first(req.headers.accept),
      userAgent: first(req.headers['user-agent']),
      npmCommand: first(req.headers['npm-command']),
      npmSession: first(req.headers['npm-session']),
      ifNoneMatch: first(req.headers['if-none-match']),
      ifModifiedSince: first(req.headers['if-modified-since']),
      status: 0,
    };
    entries.push(entry);

    const upstreamReq = http.request(
      {
        hostname: upstream.hostname,
        port: upstream.port || 80,
        path: req.url,
        method: req.method,
        headers: { ...req.headers, ...options.forwardHeaders, host: upstream.host },
      },
      (upstreamRes) => {
        entry.status = upstreamRes.statusCode ?? 502;
        entry.responseEtag = first(upstreamRes.headers.etag);
        entry.responseLastModified = first(upstreamRes.headers['last-modified']);
        entry.responseVary = first(upstreamRes.headers.vary);
        entry.responseCacheControl = first(upstreamRes.headers['cache-control']);
        entry.responseContentEncoding = first(upstreamRes.headers['content-encoding']);

        const contentType = first(upstreamRes.headers['content-type']) ?? '';
        // A gzip answer (the registry compresses large packuments, RPS-1359) is unpacked to be
        // rewritten, and goes on as identity: the recorded `responseContentEncoding` stays the
        // registry's own.
        const gzipped = entry.responseContentEncoding === 'gzip';
        const rewrite =
          options.rewriteTarballUrls === true &&
          contentType.includes('json') &&
          (!entry.responseContentEncoding || gzipped);
        if (!rewrite) {
          res.writeHead(entry.status, upstreamRes.headers);
          upstreamRes.pipe(res);
          return;
        }

        const chunks: Buffer[] = [];
        upstreamRes.on('data', (chunk: Buffer) => chunks.push(chunk));
        upstreamRes.on('end', () => {
          const received = Buffer.concat(chunks);
          const text = (gzipped ? zlib.gunzipSync(received) : received).toString('utf8');
          const body = Buffer.from(text.split(env.repoBaseUrl).join(ownBase), 'utf8');
          const headers = { ...upstreamRes.headers, 'content-length': String(body.length) };
          delete headers['transfer-encoding'];
          delete headers['content-encoding'];
          res.writeHead(entry.status, headers);
          res.end(body);
        });
      },
    );
    upstreamReq.on('error', (err) => {
      entry.status = 502;
      res.destroy(err);
    });
    if (options.captureRequestBody) {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => chunks.push(chunk));
      req.on('end', () => {
        entry.requestBody = Buffer.concat(chunks).toString('utf8');
      });
    }
    req.pipe(upstreamReq);
  });

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve());
  });
  ownBase = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

  return {
    baseUrl: ownBase,
    entries,
    under: (prefix) => entries.filter((entry) => entry.path.startsWith(prefix)),
    stop: () =>
      new Promise<void>((resolve) => {
        server.close(() => resolve());
        server.closeAllConnections();
      }),
  };
}
