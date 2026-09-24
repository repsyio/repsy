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
 * Entry point of the stub scanner container (`runners/scanner-stub.Dockerfile`): `node main.ts`, by
 * Node's own type stripping (no build step, no dependencies). Environment, named like the real
 * `repsy-scanner-trivy` where it has the same setting:
 *  - `SCANNER_API_KEY` (required): the shared secret of the `X-Scanner-Api-Key` header.
 *  - `SERVER_PORT` (default 8090).
 *  - `SCANNER_STUB_CONTROL` (default `enabled`): `disabled` turns `/control` off.
 */
import { createScannerStub } from './server.ts';

const apiKey = process.env.SCANNER_API_KEY;
if (!apiKey) {
  console.error('SCANNER_API_KEY is required');
  process.exit(1);
}
const port = Number(process.env.SERVER_PORT || 8090);
const controlEnabled = (process.env.SCANNER_STUB_CONTROL || 'enabled') !== 'disabled';

const { server } = createScannerStub({ apiKey, controlEnabled });
server.listen(port, '0.0.0.0', () => {
  console.log(`stub scanner listening on ${port} (control ${controlEnabled ? 'on' : 'off'})`);
});

for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
    server.closeAllConnections();
  });
}
