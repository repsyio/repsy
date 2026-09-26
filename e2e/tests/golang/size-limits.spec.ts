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

/**
 * RPS-1482: a real `curl -T` (there is no official Go publisher, the panel documents curl) of a package over the upload limit, on a stack started with the limits overlay
 * (`./run.sh local up --limits`, README.md "Size-limit leg"). Registered by `registerSizeLimitSpecs`
 * (`src/scenarios/size-limits.ts`); the push itself is `clients/oversize.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { golangAdapter } from '../../src/clients/golang.js';
import { pushGolang } from '../../src/clients/oversize.js';
import { registerSizeLimitSpecs } from '../../src/scenarios/size-limits.js';

registerSizeLimitSpecs({
  protocol: 'golang',
  client: 'curl -T',
  repoType: RepoType.GOLANG,
  adapter: golangAdapter,
  push: pushGolang,
  clientMessage: /curl: \(22\) The requested URL returned error: 413[\s\S]*payloadTooLarge/,
});
