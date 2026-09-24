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
 * The test side of the stub scanner's `/control` API (see `server.ts`): reads what the scanner was
 * asked to scan and overrides what it reports for one artifact name. Only the `scanner` opt-in specs
 * use it, and only when the stack was started with the scanner overlay
 * (`./run.sh local up --scanner`, README.md "Scanner stack").
 */
import type { RecordedCall, StubScript } from './server.ts';

export type { RecordedCall, StubScript };

/** The API key the overlay gives the stub and the backend unless `REPSY_SCANNER_API_KEY` says otherwise. */
export const DEFAULT_SCANNER_API_KEY = 'e2e-scanner-key';

export class ScannerStubClient {
  readonly baseUrl: string;
  private readonly apiKey: string;

  constructor(baseUrl: string, apiKey: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.apiKey = apiKey;
  }

  /** The stub the ui runner is pointed at (`REPSY_SCANNER_STUB_URL`, `REPSY_SCANNER_API_KEY`). */
  static fromEnv(): ScannerStubClient {
    return new ScannerStubClient(
      process.env.REPSY_SCANNER_STUB_URL || 'http://localhost:8090',
      process.env.REPSY_SCANNER_API_KEY || DEFAULT_SCANNER_API_KEY,
    );
  }

  private async call(method: string, path: string, body?: unknown): Promise<unknown> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        'x-scanner-api-key': this.apiKey,
        ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) {
      throw new Error(
        `stub scanner ${method} ${path} answered ${response.status}: ${await response.text()}`,
      );
    }
    return response.json();
  }

  /** Whether the stub answers `GET /health`: false (never throws) when nothing listens. */
  async isUp(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/health`, {
        signal: AbortSignal.timeout(3_000),
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  /** Every `POST /scan` the stub has seen for `artifactName` (all artifacts when omitted), oldest first. */
  async calls(artifactName?: string): Promise<RecordedCall[]> {
    const query = artifactName ? `?artifactName=${encodeURIComponent(artifactName)}` : '';
    const body = (await this.call('GET', `/control/calls${query}`)) as { calls: RecordedCall[] };
    return body.calls;
  }

  /** Overrides what scans of exactly `artifactName` do from now on (until `clearScript`). */
  async script(artifactName: string, script: StubScript): Promise<void> {
    await this.call('PUT', '/control/scripts', { artifactName, script });
  }

  async clearScript(artifactName: string): Promise<void> {
    await this.call('DELETE', `/control/scripts?artifactName=${encodeURIComponent(artifactName)}`);
  }
}
