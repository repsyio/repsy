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
 * The whole scenario catalog through `regctl image copy` (RPS-1478 part B), the same scenarios and
 * expectations as `publish-consume.spec.ts`'s `crane` run and `skopeo-catalog.spec.ts`. The
 * client-specific behaviour (`manifest get`, `tag ls`, deletes, sha512, multi-arch) is in
 * `regctl.spec.ts`. `docker[regctl] > <scenario>`; `--grep @regctl` selects it.
 */
import { regctlAdapter } from '../../src/clients/docker-regctl.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';

registerPublishConsumeLoop(regctlAdapter);
