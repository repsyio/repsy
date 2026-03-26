// @ts-check
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
import simpleImportSort from 'eslint-plugin-simple-import-sort';
import prettierConfig from 'eslint-config-prettier';

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
    },
    rules: {
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
      "**/env.d.ts"
    ]
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
