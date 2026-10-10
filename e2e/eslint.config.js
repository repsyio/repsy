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
import checkFile from 'eslint-plugin-check-file';
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

const PROCESS_ENV_MESSAGE =
  'Do not copy process.env into a child environment (it carries REPSY_ADMIN_PASSWORD and the other runner ' +
  'variables): build it with clientEnv() (src/clients/client-env.ts), or give run() { extendEnv: true } ' +
  'for a harness tool that needs the runner environment.';

const PROCESS_ENV = "MemberExpression[object.name='process'][property.name='env']";

const PROCESS_ENV_COPIES = [
  // { ...process.env, X } and [...process.env]
  `SpreadElement > ${PROCESS_ENV}`,
  // Object.assign(target, process.env)
  `CallExpression[callee.object.name='Object'][callee.property.name='assign'] > ${PROCESS_ENV}`,
  // execa(cmd, args, { env: process.env }) and the same for spawn
  `Property[key.name='env'] > ${PROCESS_ENV}`,
].map((selector) => ({ selector, message: PROCESS_ENV_MESSAGE }));

// RPS-1652 (H9): a stub body is a value of a generated OpenAPI model, written through fulfillJson<Model>() in
// src/ui/stub-responses.ts, so `tsc` breaks on an API change. A `route.fulfill({ body })` or `({ json })`
// anywhere else in the UI suite is an untyped body (a string, or `unknown`); `fulfill({ response })` (a real
// answer, passed on) and a status-only `fulfill({ status })` stay allowed.
const UNTYPED_STUB_BODY = {
  selector:
    "CallExpression[callee.property.name='fulfill'] > ObjectExpression > Property[key.name=/^(body|json|path)$/]",
  message:
    'Answer a stub with fulfillJson<Model>(route, status, body) (src/ui/stub-responses.ts), a body typed from the ' +
    'generated OpenAPI models, or fulfillText() for a body that is not JSON on purpose (RPS-1652).',
};

// RPS-1770: Repsy Cloud runs this UI suite verbatim, and its panel API carries the owner
// (`/api/repos/<owner>/<repo>/...`) where Repsy OS's does not. A repo-scoped panel API path is built with
// repoApiPath() (src/ui/routes.ts), never by interpolating into a literal. `/api/repos/counts` and
// `/api/repos/security-summary` name no repository and stay literals; so does a glob such as
// `** /api/repos/*` (use a matcher built from repoApiPath()).
const HARDCODED_REPO_API_MESSAGE =
  'Build a repo-scoped panel API path with repoApiPath() (src/ui/routes.ts): Repsy Cloud puts the owner in ' +
  'it (/api/repos/<owner>/<repo>/...) and runs this suite verbatim (RPS-1770).';
const HARDCODED_REPO_API = [
  // `/api/repos/${repo}/...`: a template chunk that ends in /api/repos/ before an expression
  'TemplateLiteral > TemplateElement[value.raw=/\\/api\\/repos\\/$/]',
  // '**/api/repos/*/contents' and '/api/repos/{repo}...' written as plain strings
  'Literal[value=/\\/api\\/repos\\/(\\*|\\{)/]',
].map((selector) => ({ selector, message: HARDCODED_REPO_API_MESSAGE }));

// RPS-2123: JS/TS naming baseline (AGENTS.md "JavaScript and TypeScript naming"). Every rule below is `warn`;
// the story that migrates a family of names flips its rule to `error`. An `I` interface prefix, the
// abbreviations URL / ID / UI written in capitals, and Uuid, Golang, Nuget and Oauth are not allowed in an
// identifier; all-caps constants and the wire names in the filter (uploadUuid, baseURL, toHaveURL) are skipped.
const FORBIDDEN_IDENTIFIER_PARTS = '^I[A-Z][a-z]|Uuid|Golang|Nuget|Oauth|(URL|ID|UI)(?![a-z])';
const SKIPPED_IDENTIFIERS = '^([A-Z0-9_]+|uploadUuid|baseURL|toHaveURL)$';

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
    files: ['src/**/*.ts', 'tests/**/*.ts', '*.ts'],
    plugins: { 'check-file': checkFile },
    rules: {
      '@typescript-eslint/naming-convention': [
        'warn',
        // Quoted keys (HTTP headers, JSON wire names) are a contract, not a naming choice.
        { selector: 'default', modifiers: ['requiresQuotes'], format: null },
        {
          selector: 'default',
          format: null,
          filter: { regex: SKIPPED_IDENTIFIERS, match: false },
          custom: { regex: FORBIDDEN_IDENTIFIER_PARTS, match: false },
        },
      ],
      'check-file/filename-naming-convention': [
        'warn',
        { '**/*.ts': 'KEBAB_CASE' },
        { ignoreMiddleExtensions: true },
      ],
      '@typescript-eslint/explicit-member-accessibility': ['warn', { accessibility: 'no-public' }],
      'no-restricted-imports': [
        'warn',
        {
          patterns: [
            {
              regex: 'api/generated/(?!index(\\.js)?$).+',
              message:
                'Import the generated client from src/api/generated/index.js, not a deep path.',
            },
          ],
        },
      ],
    },
  },
  {
    // RPS-1468 / RPS-1446: a child process gets an explicit environment, never the runner's. `process.env`
    // carries REPSY_ADMIN_PASSWORD and every other runner variable, so copying it whole into a child's
    // environment (`{ ...process.env }`, `Object.assign(env, process.env)`, `env: process.env`) hands them
    // to a client, its plugins and the scripts it runs. Build a client's environment with clientEnv()
    // (src/clients/client-env.ts); a harness tool that needs the runner's environment (docker compose)
    // takes `run(..., { extendEnv: true })`. Reading single names (`process.env.PATH`), filtering
    // `Object.entries(process.env)` and passing `process.env` to a function stay allowed.
    files: ['src/**/*.ts', 'tests/**/*.ts'],
    rules: {
      'no-restricted-syntax': ['error', ...PROCESS_ENV_COPIES],
    },
  },
  {
    files: ['src/ui/**/*.ts', 'tests/ui/**/*.ts'],
    ignores: ['src/ui/stub-responses.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...PROCESS_ENV_COPIES,
        UNTYPED_STUB_BODY,
        ...HARDCODED_REPO_API,
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
            'expectSealed',
            'runScenario',
            'expectClientAgrees',
            'expectNothingStored',
            'expectResolvedContent',
            'expectSnapshotFollowedThroughMetadata',
            'expectPut',
            'expectPublish',
            'expectOci',
            'expectMsgId',
            'expectDialogContract',
            'expectContract',
            'expectFailure',
            'expectPagingSweep',
            'expectCovers',
            'expectVisual',
            'expectSessionEndedOnNextRequest',
            'expectDocsLink',
          ],
        },
      ],
      // This harness's whole point is data-driven, catalog-generated tests (plan section "Scenario
      // model"): `{ tag: scenario.tags }` is necessarily a runtime value, not a literal the rule can
      // statically validate. catalog.ts is the single place tags are written by hand and typed as
      // `` `@${string}` ``-shaped strings there; that is this project's real enforcement of the
      // format this rule would otherwise check.
      'playwright/valid-test-tags': 'off',
      // RPS-2123: a title states the behaviour; no `should`, no `Success -` / `Fail -` prefixes.
      'playwright/valid-title': ['warn', { disallowedWords: ['should', 'Success', 'Fail'] }],
    },
  },
  prettier,
);
