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
 * Which `PanelBackend` a run uses (README "Targets"):
 *
 *  1. `REPSY_E2E_BACKEND_MODULE`, when set, names a module (an absolute path, a path relative to the
 *     working directory, a `file:` URL or a package name) that is `import()`ed. It exports a factory
 *     `createPanelBackend(baseUrl: string): PanelBackend | Promise<PanelBackend>` as its named export
 *     of that name or as its default export.
 *  2. Otherwise `env.target` picks a built-in backend. Every current target (`local`, `ci`, `remote`)
 *     is a Repsy OS instance, so that is `OsPanelBackend`.
 *
 * Playwright re-imports the test files (and everything they import) in every worker process, so the
 * backend cannot be registered once from `playwright.config.ts`: a registration made there would not
 * exist in the workers. Whoever needs a backend (`fixtures.ts`, the sweep, the specs that log a second
 * user in) resolves it itself through `createPanelBackend`. The environment variable is what reaches
 * every worker, and it is read when a backend is created, not when this module is imported.
 */
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { env, type RepsyTarget } from '../env.js';
import type { LoginInfo, PanelBackend } from './panel-backend.js';

export const BACKEND_MODULE_ENV = 'REPSY_E2E_BACKEND_MODULE';

export type PanelBackendFactory = (baseUrl: string) => PanelBackend | Promise<PanelBackend>;

/** The built-in backend of every target. Lazy, so choosing another backend never loads the OS one. */
const BUILT_IN: Record<RepsyTarget, () => Promise<PanelBackendFactory>> = {
  local: builtInOs,
  ci: builtInOs,
  remote: builtInOs,
};

async function builtInOs(): Promise<PanelBackendFactory> {
  const { OsPanelBackend } = await import('./os-panel-backend.js');
  return (baseUrl) => new OsPanelBackend(baseUrl);
}

/** The URL `import()` takes for a module specifier: paths are resolved against the working directory. */
function moduleUrl(specifier: string): string {
  if (specifier.startsWith('file:')) {
    return specifier;
  }
  if (specifier.startsWith('.') || isAbsolute(specifier)) {
    return pathToFileURL(resolve(process.cwd(), specifier)).href;
  }
  return specifier;
}

const factories = new Map<string, Promise<PanelBackendFactory>>();

async function loadBackendModule(specifier: string): Promise<PanelBackendFactory> {
  let loaded: Record<string, unknown>;
  try {
    loaded = (await import(moduleUrl(specifier))) as Record<string, unknown>;
  } catch (err) {
    throw new Error(`${BACKEND_MODULE_ENV}="${specifier}" could not be imported`, { cause: err });
  }
  const factory = loaded.createPanelBackend ?? loaded.default;
  if (typeof factory !== 'function') {
    throw new Error(
      `${BACKEND_MODULE_ENV}="${specifier}" must export a function \`createPanelBackend(baseUrl)\` ` +
        '(named or default) that returns a PanelBackend',
    );
  }
  return factory as PanelBackendFactory;
}

/**
 * The factory of this run's backend. `backendModule` overrides `REPSY_E2E_BACKEND_MODULE` (for a test
 * of the registry itself); an empty value counts as unset.
 */
export function resolvePanelBackendFactory(
  backendModule: string | undefined = process.env[BACKEND_MODULE_ENV],
): Promise<PanelBackendFactory> {
  if (!backendModule) {
    return BUILT_IN[env.target]();
  }
  let factory = factories.get(backendModule);
  if (!factory) {
    factory = loadBackendModule(backendModule);
    factories.set(backendModule, factory);
  }
  return factory;
}

/** A new, not yet logged in backend for the panel at `baseUrl` (the run's `REPSY_API_BASE_URL`). */
export async function createPanelBackend(baseUrl: string = env.apiBaseUrl): Promise<PanelBackend> {
  return (await resolvePanelBackendFactory())(baseUrl);
}

/** Logs `username` in through a fresh backend (its own session, apart from the admin one of `panelApi`). */
export async function loginPanel(username: string, password: string): Promise<LoginInfo> {
  return (await createPanelBackend()).login(username, password);
}
