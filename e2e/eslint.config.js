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

import pluginJs from '@eslint/js';
import playwright from 'eslint-plugin-playwright';
import prettier from 'eslint-config-prettier';
import tseslint from 'typescript-eslint';

const IGNORES = [
  'node_modules/**',
  'src/api/generated/**',
  'playwright-report/**',
  'test-results/**',
  // Rendered verbatim into an isolated work directory by clients/maven.ts / clients/npm.ts /
  // clients/cargo.ts / clients/nuget.ts (mustache templates of the tiny publishable test packages) --
  // not part of this project's own source.
  'src/packages/**',
];

export default tseslint.config(
  { ignores: IGNORES },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { varsIgnorePattern: '^_', argsIgnorePattern: '^_' },
      ],
    },
  },
  {
    files: ['tests/**/*.ts'],
    ...playwright.configs['flat/recommended'],
    rules: {
      ...playwright.configs['flat/recommended'].rules,
      // expectOutcome() and the other expect*() helpers (tests/maven/*.spec.ts) wrap expect() so
      // every scenario gets the same failure message; the rule only recognises calls to `expect`
      // itself.
      'playwright/expect-expect': [
        'warn',
        {
          assertFunctionNames: [
            'expectOutcome',
            'runScenario',
            'expectClientAgrees',
            'expectNothingStored',
            'expectResolvedContent',
            'expectSnapshotFollowedThroughMetadata',
            'expectPut',
            'expectPublish',
            'expectOci',
          ],
        },
      ],
      // This harness's whole point is data-driven, catalog-generated tests (plan section "Scenario
      // model"): `{ tag: scenario.tags }` is necessarily a runtime value, not a literal the rule can
      // statically validate. catalog.ts is the single place tags are written by hand and typed as
      // `` `@${string}` ``-shaped strings there; that is this project's real enforcement of the
      // format this rule would otherwise check.
      'playwright/valid-test-tags': 'off',
    },
  },
  prettier,
);
