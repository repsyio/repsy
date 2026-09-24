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
 * The scenario-driven Gradle plugin suite for the Groovy DSL (`build.gradle`, RPS-133): the shared catalog's
 * RELEASE scenarios (authentication, tokens, override, the releases switch) run with a Gradle plugin
 * as the artifact: published with the real `gradle publish`, applied by a real build from
 * `pluginManagement { repositories }` (`clients/gradle-plugin-adapter.ts`), plus the plugin checks the
 * catalog cannot express (`scenarios/gradle-plugin-extras.ts`).
 */
import { gradlePluginAdapter } from '../../src/clients/gradle-plugin-adapter.js';
import { test } from '../../src/scenarios/fixtures.js';
import { registerGradlePluginExtras } from '../../src/scenarios/gradle-plugin-extras.js';
import { registerPublishConsumeLoop } from '../../src/scenarios/loop.js';

// A cold Gradle per publish and per applied plugin, and a plugin build compiles its class.
test.describe.configure({ timeout: 420_000 });

registerPublishConsumeLoop(gradlePluginAdapter('groovy'));
registerGradlePluginExtras('groovy');
