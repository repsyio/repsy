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
 * A stand-in for the Repsy CLI's loopback listener (RPS-2039): an HTTP server on the loopback address of the machine
 * the browser runs on, which records what the panel's `/cli/auth` page POSTs to its `redirect_uri` and answers with a
 * small page, like the CLI's "you can close this tab". The browser and the test run on one machine, so the page can
 * reach it the way it reaches the real CLI.
 */
import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';

export interface CallbackRequest {
  method: string;
  /** Path and query, as received. */
  url: string;
  headers: IncomingHttpHeaders;
  /** The raw body. */
  body: string;
  /** The body read as `application/x-www-form-urlencoded`. */
  form: URLSearchParams;
}

export type LoopbackHost = '127.0.0.1' | '::1';

export class LoopbackCallback {
  private constructor(
    private readonly server: Server,
    private readonly host: LoopbackHost,
    readonly port: number,
    /** What the listener received, in order. */
    readonly requests: CallbackRequest[],
  ) {}

  /** Listens on a free port of `host`; rejects when the machine has no such address (no IPv6 loopback). */
  static async listen(host: LoopbackHost = '127.0.0.1'): Promise<LoopbackCallback> {
    const requests: CallbackRequest[] = [];
    const server = createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => chunks.push(chunk));
      req.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        requests.push({
          method: req.method ?? '',
          url: req.url ?? '',
          headers: req.headers,
          body,
          form: new URLSearchParams(body),
        });
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end('<!doctype html><title>Repsy CLI</title><p>Signed in. You can close this tab.</p>');
      });
    });
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject);
      server.listen(0, host, () => resolve());
    });
    const address = server.address();
    if (address === null || typeof address === 'string') {
      throw new Error('the loopback listener has no port');
    }
    return new LoopbackCallback(server, host, address.port, requests);
  }

  /** `http://127.0.0.1:<port>/callback` or `http://[::1]:<port>/callback`. */
  url(path = '/callback'): string {
    const host = this.host === '::1' ? '[::1]' : this.host;
    return `http://${host}:${this.port}${path}`;
  }

  /** Stops listening. Safe to call twice. */
  async close(): Promise<void> {
    if (!this.server.listening) {
      return;
    }
    this.server.closeAllConnections();
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
  }
}
