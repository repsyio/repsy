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
 * The scenario-driven Apache Ivy suite: the whole shared catalog (`scenarios/catalog.ts`) run through
 * the real `ant` with `ivy:publish` and `ivy:retrieve` (`clients/ivy-adapter.ts`), against an ordinary
 * Repsy Maven repository (RPS-135). The real-client tests are in `ivy-client.spec.ts`.
 */
import { ivyAdapter } from '../../src/clients/ivy-adapter.js';
import { test } from '../../src/scenarios/fixtures.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';

// A cold Ant JVM (and Ivy's settings and resolve) per publish and resolve.
test.describe.configure({ timeout: 240_000 });

registerPublishConsumeLoop(ivyAdapter);
