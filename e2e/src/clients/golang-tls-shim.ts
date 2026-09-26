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
 * An in-process TLS terminator for a credentialed real `go` invocation, next to `golang.ts`. Exists
 * because of one decisive fact read from `cmd/go/internal/web/http.go`'s `get()` and confirmed live:
 *
 *  - For an explicit `http://` `GOPROXY` URL, `net/http`'s own `send()` still sets `Authorization:
 *    Basic ...` from URL userinfo whenever no such header is already present, but the `go` command's
 *    OWN `web` package refuses first: `if insecure.User != nil && security != Insecure { return
 *    fmt.Errorf("refusing to pass credentials to insecure URL: %s", ...) }`, and a module-proxy fetch
 *    never runs with `Insecure` security (that mode is reserved for `GOINSECURE`'s direct/VCS lookups,
 *    not `GOPROXY`). Confirmed live/H3: `go mod download -json` against `http://user:pass@host/...`
 *    exits 1 with exactly that message text before any request is sent -- there is no flag to
 *    override it.
 *  - Over `https://`, URL userinfo works completely normally (`net/http`'s `send()` sets the header
 *    unconditionally on scheme). Confirmed live/H4: the SAME command succeeds against an `https://`
 *    URL whose leaf certificate is merely readable via `SSL_CERT_FILE` (`crypto/x509`'s
 *    `SystemCertPool` doc: "SSL_CERT_FILE ... can be used to override the system default locations"),
 *    with no `--ca`/intermediate needed -- a self-signed leaf that is itself in the trusted set is
 *    accepted. Both `user:password` and `token:<deploy-token>`-shaped credentials work identically
 *    (`ProtocolAuthService.handleBasicAuth` tries the password as a deploy token first regardless of
 *    username).
 *
 * So a credentialed consume against this harness's plain-http stack needs an `https://` endpoint in
 * front of it. Chosen: a lazily started, per-worker-process Node `https.createServer` reverse proxy
 * bound to `127.0.0.1:0` (an ephemeral port, so parallel Playwright workers never collide, confirmed
 * live/H15), forwarding every request verbatim to `env.repoBaseUrl` and copying the response back
 * unchanged. `server.unref()` so a worker process exits normally without an explicit shutdown. The
 * certificate/key are generated ONCE, at `golang.Dockerfile` build time, with Go's own
 * `crypto/tls/generate_cert.go` (confirmed live: `go run .../generate_cert.go --host
 * localhost,127.0.0.1 ...` produces a leaf `SSL_CERT_FILE` alone is enough to trust) -- never at test
 * time, and never checked in (throwaway, test-only, world-readable by design).
 *
 * An anonymous consume, or one against a target that is already `https://` (a remote instance), never
 * needs the shim at all: H2 confirms a plain, credential-less `http://` `GOPROXY` works unmodified.
 */
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import type { AddressInfo } from 'node:net';

import { env } from '../env.js';
import { repoPath } from '../repo-url.js';
import type { MaterializedCredential } from '../scenarios/world.js';

export interface ShimTraceEntry {
  method: string;
  path: string;
  hasAuthorization: boolean;
}

interface Shim {
  baseUrl: string;
  trace: ShimTraceEntry[];
}

function startShim(): Promise<Shim> {
  return new Promise((resolve, reject) => {
    const certPath = process.env.REPSY_E2E_TLS_CERT;
    const keyPath = process.env.REPSY_E2E_TLS_KEY;
    if (!certPath || !keyPath) {
      reject(
        new Error(
          'golang-tls-shim: REPSY_E2E_TLS_CERT/REPSY_E2E_TLS_KEY are not set -- only the golang ' +
            'runner image (runners/golang.Dockerfile) generates and exports them.',
        ),
      );
      return;
    }

    const key = fs.readFileSync(keyPath);
    const cert = fs.readFileSync(certPath);
    const upstream = new URL(env.repoBaseUrl);
    const trace: ShimTraceEntry[] = [];

    const server = https.createServer({ key, cert }, (req, res) => {
      trace.push({
        method: req.method ?? '',
        path: req.url ?? '',
        hasAuthorization: Boolean(req.headers.authorization),
      });

      const upstreamReq = http.request(
        {
          hostname: upstream.hostname,
          port: upstream.port || 80,
          path: req.url,
          method: req.method,
          headers: { ...req.headers, host: upstream.host },
        },
        (upstreamRes) => {
          res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
          upstreamRes.pipe(res);
        },
      );
      upstreamReq.on('error', (err) => {
        res.destroy(err);
      });
      req.pipe(upstreamReq);
    });

    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.unref();
      const { port } = server.address() as AddressInfo;
      resolve({ baseUrl: `https://127.0.0.1:${port}`, trace });
    });
  });
}

let shimPromise: Promise<Shim> | undefined;

/** One shim per worker process, started lazily on first use. */
export function ensureTlsShim(): Promise<Shim> {
  shimPromise ??= startShim();
  return shimPromise;
}

/** Whether a `go` invocation with `credential` against `env.repoBaseUrl` needs the shim at all: only
 *  a CREDENTIALED request against a PLAIN-HTTP target (H3); an anonymous request, or a target that is
 *  already `https://`, never does (H2). */
export function needsTlsShim(credential: MaterializedCredential): boolean {
  if (credential.transport !== 'basic' || !credential.username) {
    return false;
  }
  return new URL(env.repoBaseUrl).protocol === 'http:';
}

/** `<scheme>://<user>:<secret>@<host>` with `credential`'s userinfo percent-encoded (`url.Parse`
 *  decodes it, `basicAuth` uses the decoded values) -- no credentials embedded at all for
 *  `anonymous`. */
function withCredentials(base: string, credential: MaterializedCredential): string {
  if (credential.transport !== 'basic' || !credential.username) {
    return base;
  }
  const url = new URL(base);
  const user = encodeURIComponent(credential.username);
  const pass = encodeURIComponent(credential.password ?? '');
  return `${url.protocol}//${user}:${pass}@${url.host}`;
}

/**
 * The `GOPROXY` value (sans the `,off` fallback, which `goEnv` in `golang.ts` appends) for a `go`
 * invocation authenticating as `credential` against `repoName`: routed through the shim only when
 * `needsTlsShim` says so, straight at `env.repoBaseUrl` otherwise (with credentials embedded directly
 * when `env.repoBaseUrl` is itself already `https://`, e.g. a remote target). Returns the shim's
 * certificate path too, so the caller can set `SSL_CERT_FILE` only when it actually routed through
 * the shim.
 */
export async function goProxyUrlFor(
  repoName: string,
  credential: MaterializedCredential,
): Promise<{ proxyUrl: string; certFile?: string }> {
  if (!needsTlsShim(credential)) {
    return { proxyUrl: `${withCredentials(env.repoBaseUrl, credential)}/${repoPath(repoName)}` };
  }

  const shim = await ensureTlsShim();
  return {
    proxyUrl: `${withCredentials(shim.baseUrl, credential)}/${repoPath(repoName)}`,
    certFile: process.env.REPSY_E2E_TLS_CERT,
  };
}

/** The shim's recorded request trace so far, for a dedicated test pinning the `go` command's own wire
 *  sequence (H8/H19) -- `undefined` when the shim was never started in this worker process. */
export async function shimTraceSoFar(): Promise<ShimTraceEntry[] | undefined> {
  if (!shimPromise) {
    return undefined;
  }
  const shim = await shimPromise;
  return shim.trace;
}
