// @ts-check
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
import simpleImportSort from 'eslint-plugin-simple-import-sort';
import prettierConfig from 'eslint-config-prettier';
import checkFile from 'eslint-plugin-check-file';
import rxjsX from 'eslint-plugin-rxjs-x';

// RPS-2123: JS/TS naming baseline (AGENTS.md "JavaScript and TypeScript naming"). Every rule below is
// `warn`; the story that migrates a family of names flips its rule to `error`.
//   - `I` interface prefix, and the abbreviations URL / ID / UI written in capitals, plus Uuid, Golang
//     and Oauth, are not allowed in an identifier. All-caps constants and the wire names listed in the
//     filter (uploadUuid, baseURL, toHaveURL) are skipped.
const FORBIDDEN_IDENTIFIER_PARTS = '^I[A-Z][a-z]|Uuid|Golang|Oauth|(URL|ID|UI)(?![a-z])';
const SKIPPED_IDENTIFIERS = '^([A-Z0-9_]+|uploadUuid|baseURL|toHaveURL)$';

export default tseslint.config(
  {
    files: ["**/*.ts"],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...tseslint.configs.stylistic,
      ...angular.configs.tsRecommended,
      prettierConfig
    ],
    processor: angular.processInlineTemplates,
    plugins: {
      "simple-import-sort": simpleImportSort,
      "check-file": checkFile,
      "rxjs-x": rxjsX,
    },
    languageOptions: {
      parserOptions: {
        projectService: true,
      },
    },
    rules: {
      "@typescript-eslint/naming-convention": [
        "warn",
        // Quoted keys (HTTP headers, JSON wire names) are a contract, not a naming choice.
        { selector: "default", modifiers: ["requiresQuotes"], format: null },
        {
          selector: "default",
          format: null,
          filter: { regex: SKIPPED_IDENTIFIERS, match: false },
          custom: { regex: FORBIDDEN_IDENTIFIER_PARTS, match: false },
        },
      ],
      // RPS-2124: `Golang` is `Go`. The declaration forms are an error; the wire values (`type=golang`,
      // `RepoType.GOLANG`, generated operation ids such as `listGolangModules`) are references, not declarations.
      "no-restricted-syntax": [
        "error",
        ...[
          "ClassDeclaration[id.name=/[Gg]olang/]",
          "FunctionDeclaration[id.name=/[Gg]olang/]",
          "VariableDeclarator[id.name=/[Gg]olang/]",
          "TSInterfaceDeclaration[id.name=/[Gg]olang/]",
          "TSTypeAliasDeclaration[id.name=/[Gg]olang/]",
          "MethodDefinition[key.name=/[Gg]olang/]",
          "PropertyDefinition[key.name=/[Gg]olang/]",
        ].map((selector) => ({ selector, message: "Write Golang as Go (RPS-2124)." })),
        // RPS-2125: `Nuget` is `NuGet`. Declarations only: the generated `NugetPackagesApi` and its operation ids
        // (`getNugetPackage`) and `RepoType.Nuget` are references to generated code and stay.
        ...[
          "ClassDeclaration[id.name=/Nuget/]",
          "FunctionDeclaration[id.name=/Nuget/]",
          "VariableDeclarator[id.name=/Nuget/]",
          "TSInterfaceDeclaration[id.name=/Nuget/]",
          "TSTypeAliasDeclaration[id.name=/Nuget/]",
          "MethodDefinition[key.name=/Nuget/]",
          "PropertyDefinition[key.name=/Nuget/]",
        ].map((selector) => ({ selector, message: "Write Nuget as NuGet (RPS-2125)." })),
        // RPS-2127: a data type is a role (`Info`, `Item`, `Form`, `Payload`), never `Dto` or `Model`.
        ...[
          "ClassDeclaration[id.name=/(Dto|DTO|Model)$/]",
          "TSInterfaceDeclaration[id.name=/(Dto|DTO|Model)$/]",
          "TSTypeAliasDeclaration[id.name=/(Dto|DTO|Model)$/]",
        ].map((selector) => ({
          selector,
          message: "Name a data type by its role (Info, Item, Form, Payload), not Dto or Model (RPS-2127).",
        })),
        // RPS-2132: an API error code is a client contract and is written once, in ERROR_CODES
        // (src/app/shared/constants/error-codes.ts); everything else compares against the constant.
        {
          selector: "Literal[value=/^(accessDenied|accessNotAllowed|invalidCredentials|loginRequired|refreshTokenExpired|resourceBusy|sessionExpired)$/]",
          message: "Use ERROR_CODES from shared/constants/error-codes instead of an inline error code (RPS-2132).",
        },
      ],
      "check-file/filename-naming-convention": [
        "error",
        { "**/*.ts": "KEBAB_CASE" },
        { ignoreMiddleExtensions: true },
      ],
      "@typescript-eslint/explicit-member-accessibility": ["warn", { accessibility: "no-public" }],
      "rxjs-x/finnish": "warn",
      "no-restricted-imports": [
        "warn",
        {
          patterns: [
            {
              regex: "generated/api/.+",
              message: "Import from the generated API index (src/generated/api), not a deep path.",
            },
          ],
        },
      ],
      '@typescript-eslint/no-empty-function': 'off',
      "@angular-eslint/prefer-inject": "off",
      "no-useless-escape": "off",
      "curly": ["error", "all"],
      "@angular-eslint/directive-selector": [
        "error",
        {
          type: "attribute",
          prefix: "app",
          style: "camelCase",
        },
      ],
      "@angular-eslint/component-selector": [
        "error",
        {
          type: "element",
          prefix: "app",
          style: "kebab-case",
        },
      ],
      "simple-import-sort/imports": [
        "error",
        {
          groups: [['^\\u0000'], ['^@?(?!baf)\\w'], ['^@baf?\\w'], ['^\\w'], ['^[^.]'], ['^\\.']],
        },
      ],
      "simple-import-sort/exports": "error",
    },
    ignores: [
      "**/env.d.ts",
      "src/generated/**"
    ]
  },
  {
    files: ["src/app/shared/constants/error-codes.ts"],
    rules: { "no-restricted-syntax": "off" },
  },
  {
    files: ["**/*.html"],
    extends: [
      ...angular.configs.templateRecommended,
      ...angular.configs.templateAccessibility,
      prettierConfig
    ],
    rules: {
      "no-unused-expressions": "off"
    },
  }
);
