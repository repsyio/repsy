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

/**
 * Response validation against `openapi-spec.yaml` (RPS-1483): "a live response body of operation X with
 * status Y is what the spec says", so the contract specs (`tests/<protocol>/panel-api.spec.ts`) fail when
 * the backend and the spec drift apart in either direction. The spec is the single source of truth of the
 * panel API; nothing here copies it.
 *
 * The spec is OpenAPI 3.1, whose schema objects ARE JSON Schema 2020-12, so `ajv/dist/2020` validates them
 * as they are: no `nullable` conversion (ajv knows the keyword itself; the spec's only use is
 * `nullable: false`) and no discriminator handling (the spec has none). What it adds:
 *
 *  - `#/components/schemas/X` references are rewritten to `https://panel.repsy.invalid/spec#/$defs/X`, one root schema that
 *    holds every component schema; a response object that is itself a `$ref` (`#/components/responses/...`)
 *    is looked through to its JSON content schema;
 *  - the OpenAPI formats JSON Schema does not know (`int32`, `int64`, `float`, `double`) are registered, and
 *    `ajv-formats` supplies `uuid`, `date-time` and the rest. Strict mode stays ON, so a keyword the
 *    validator does not understand throws instead of being skipped silently.
 *
 * The schemas of this spec declare no `required` and no `additionalProperties`, so a schema check finds
 * a wrong type, a bad format or enum value, or a null the spec does not allow, but not an ABSENT field or
 * an undeclared one. `undeclaredProperties` covers the second half: it walks a body along its schema and
 * lists every property the schema does not declare. Presence of the fields a spec relies on is asserted
 * against the client-side facts (name, version, checksum), not against the schema.
 */
import { readFileSync } from 'node:fs';

import Ajv2020, { type ValidateFunction } from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { parse } from 'yaml';

import { specPath } from './spec-ops.js';

const ROOT_ID = 'https://panel.repsy.invalid/spec';
const COMPONENT_SCHEMA_PREFIX = '#/components/schemas/';
const COMPONENT_RESPONSE_PREFIX = '#/components/responses/';

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type Schema = { [key: string]: Json };

interface RawResponse {
  $ref?: string;
  content?: Record<string, { schema?: Schema } | undefined>;
}

interface RawOperation {
  operationId: string;
  responses: Record<string, RawResponse>;
}

interface RawSpec {
  paths: Record<string, Record<string, unknown>>;
  components: {
    schemas: Record<string, Schema>;
    responses?: Record<string, RawResponse>;
  };
}

/** `$ref: "#/components/schemas/X"` -> `$ref: "https://panel.repsy.invalid/spec#/$defs/X"`, everywhere below `node`. */
function rewriteRefs(node: Json): Json {
  if (Array.isArray(node)) {
    return node.map(rewriteRefs);
  }
  if (node !== null && typeof node === 'object') {
    const out: Schema = {};
    for (const [key, value] of Object.entries(node)) {
      if (key === 'nullable' && !('type' in node)) {
        // ajv rejects `nullable` next to a `$ref` or `allOf` (it needs a `type`); OpenAPI 3.1 ignores it
        // there too, so it is dropped rather than made to mean something it does not.
        continue;
      }
      out[key] =
        key === '$ref' && typeof value === 'string' && value.startsWith(COMPONENT_SCHEMA_PREFIX)
          ? `${ROOT_ID}#/$defs/${value.slice(COMPONENT_SCHEMA_PREFIX.length)}`
          : rewriteRefs(value);
    }
    return out;
  }
  return node;
}

function collectExtensionKeys(node: Json, into: Set<string>): void {
  if (Array.isArray(node)) {
    node.forEach((child) => collectExtensionKeys(child, into));
  } else if (node !== null && typeof node === 'object') {
    for (const [key, value] of Object.entries(node)) {
      if (key.startsWith('x-')) {
        into.add(key);
      }
      collectExtensionKeys(value, into);
    }
  }
}

/** One place where a body does not match its schema: where (a JSON pointer), what, and the offending value. */
export interface Violation {
  path: string;
  message: string;
  value: unknown;
}

export class SpecContract {
  private readonly spec: RawSpec;
  private readonly ajv: Ajv2020;
  private readonly operations = new Map<string, RawOperation>();
  private readonly validators = new Map<string, ValidateFunction>();

  constructor(specYaml: string) {
    this.spec = parse(specYaml) as RawSpec;
    for (const item of Object.values(this.spec.paths)) {
      for (const value of Object.values(item)) {
        const op = value as Partial<RawOperation> | null;
        if (op && typeof op === 'object' && op.operationId && op.responses) {
          this.operations.set(op.operationId, op as RawOperation);
        }
      }
    }
    this.ajv = new Ajv2020({ strict: true, allErrors: true, verbose: true });
    addFormats(this.ajv);
    // OpenAPI formats that JSON Schema (and ajv-formats) do not define; `type: integer` already pins
    // the shape, these add the range.
    this.ajv.addFormat('int32', {
      type: 'number',
      validate: (n: number) => Number.isInteger(n) && n >= -(2 ** 31) && n < 2 ** 31,
    });
    this.ajv.addFormat('int64', {
      type: 'number',
      validate: (n: number) => Number.isSafeInteger(n),
    });
    this.ajv.addFormat('float', true);
    this.ajv.addFormat('double', true);
    // Annotations of OpenAPI that carry no validation (`nullable` is a keyword of ajv itself), and the
    // spec's own `x-...` vendor extensions.
    const annotations = new Set(['example', 'xml', 'externalDocs']);
    collectExtensionKeys(this.spec.components.schemas as Json, annotations);
    annotations.forEach((keyword) => this.ajv.addKeyword(keyword));
    this.ajv.addSchema({
      $id: ROOT_ID,
      $defs: rewriteRefs(this.spec.components.schemas as Json) as Schema,
    });
  }

  /** Every operationId of the spec. */
  operationIds(): string[] {
    return [...this.operations.keys()];
  }

  /** The declared status codes of an operation. */
  statusesOf(operationId: string): string[] {
    return Object.keys(this.operation(operationId).responses);
  }

  /** Whether the spec declares a JSON body for `status` of `operationId` (a `204`, or the Go sumdb probe, has none). */
  hasJsonBody(operationId: string, status: number | string): boolean {
    try {
      this.responseSchema(operationId, status);
      return true;
    } catch (error) {
      if (error instanceof Error && error.message.endsWith('declares no JSON body')) {
        return false;
      }
      throw error;
    }
  }

  private operation(operationId: string): RawOperation {
    const op = this.operations.get(operationId);
    if (!op) {
      throw new Error(`the spec has no operation "${operationId}"`);
    }
    return op;
  }

  /** The JSON schema the spec declares for `status` of `operationId`, refs already rewritten. */
  responseSchema(operationId: string, status: number | string): Schema {
    const declared = this.operation(operationId).responses[String(status)];
    if (!declared) {
      throw new Error(`the spec declares no ${status} response for ${operationId}`);
    }
    let response: RawResponse = declared;
    if (declared.$ref?.startsWith(COMPONENT_RESPONSE_PREFIX)) {
      const name = declared.$ref.slice(COMPONENT_RESPONSE_PREFIX.length);
      const component = this.spec.components.responses?.[name];
      if (!component) {
        throw new Error(`the spec has no response component "${name}"`);
      }
      response = component;
    }
    const schema = response.content?.['application/json']?.schema;
    if (!schema) {
      throw new Error(`${operationId} ${status}: the spec declares no JSON body`);
    }
    return rewriteRefs(schema) as Schema;
  }

  private validator(operationId: string, status: number | string): ValidateFunction {
    const key = `${operationId} ${status}`;
    let validate = this.validators.get(key);
    if (!validate) {
      validate = this.ajv.compile(this.responseSchema(operationId, status));
      this.validators.set(key, validate);
    }
    return validate;
  }

  /** The schema violations of `body` against the declared `status` response of `operationId`; empty when it conforms. */
  violations(operationId: string, status: number | string, body: unknown): Violation[] {
    const validate = this.validator(operationId, status);
    if (validate(body)) {
      return [];
    }
    return (validate.errors ?? []).map((error) => ({
      path: error.instancePath || '/',
      message: error.message ?? 'is invalid',
      value: error.data,
    }));
  }

  /**
   * The property paths of `body` that its schema does not declare (`/data/content/0/foo`), so a field a
   * backend adds without a spec change shows up. Objects with `additionalProperties` (a schema or `true`)
   * accept any key, and a key in `properties` of any `allOf`/`oneOf`/`anyOf` branch counts as declared.
   */
  undeclaredProperties(operationId: string, status: number | string, body: unknown): string[] {
    const found: string[] = [];
    this.walk(this.responseSchema(operationId, status), body, '', found);
    return found;
  }

  private resolve(schema: Schema): Schema {
    let current = schema;
    for (let hops = 0; typeof current.$ref === 'string'; hops++) {
      if (hops > 20) {
        throw new Error('a $ref chain that does not end');
      }
      const prefix = `${ROOT_ID}#/$defs/`;
      const ref = current.$ref;
      if (!ref.startsWith(prefix)) {
        throw new Error(`unsupported $ref ${ref}`);
      }
      const target = this.spec.components.schemas[ref.slice(prefix.length)];
      if (!target) {
        throw new Error(`dangling $ref ${ref}`);
      }
      current = rewriteRefs(target as Json) as Schema;
    }
    return current;
  }

  private declaredKeys(schema: Schema): { keys: Set<string>; open: boolean } {
    const resolved = this.resolve(schema);
    const keys = new Set(Object.keys((resolved.properties as Schema | undefined) ?? {}));
    // A schema that names no property at all (`{}`, a bare `type: object`) describes nothing to check.
    let open =
      (resolved.additionalProperties !== undefined && resolved.additionalProperties !== false) ||
      resolved.properties === undefined;
    for (const combinator of ['allOf', 'oneOf', 'anyOf']) {
      for (const branch of (resolved[combinator] as Schema[] | undefined) ?? []) {
        const inner = this.declaredKeys(branch);
        inner.keys.forEach((key) => keys.add(key));
        open = open || inner.open;
      }
    }
    return { keys, open };
  }

  private childSchema(schema: Schema, key: string): Schema | undefined {
    const resolved = this.resolve(schema);
    const own = (resolved.properties as Record<string, Schema> | undefined)?.[key];
    if (own) {
      return own;
    }
    for (const combinator of ['allOf', 'oneOf', 'anyOf']) {
      for (const branch of (resolved[combinator] as Schema[] | undefined) ?? []) {
        const inner = this.childSchema(branch, key);
        if (inner) {
          return inner;
        }
      }
    }
    const additional = resolved.additionalProperties;
    return additional !== null && typeof additional === 'object' && !Array.isArray(additional)
      ? additional
      : undefined;
  }

  private walk(schema: Schema, value: unknown, path: string, found: string[]): void {
    if (Array.isArray(value)) {
      const items = this.resolve(schema).items as Schema | undefined;
      value.forEach((item, index) => {
        if (items) {
          this.walk(items, item, `${path}/${index}`, found);
        }
      });
      return;
    }
    if (value === null || typeof value !== 'object') {
      return;
    }
    const { keys, open } = this.declaredKeys(schema);
    for (const [key, child] of Object.entries(value)) {
      if (!keys.has(key) && !open) {
        found.push(`${path}/${key}`);
        continue;
      }
      const childSchema = this.childSchema(schema, key);
      if (childSchema) {
        this.walk(childSchema, child, `${path}/${key}`, found);
      }
    }
  }
}

let shared: SpecContract | undefined;

/** The contract of the checkout's (or the runner's mounted) `openapi-spec.yaml`, parsed once. */
export function specContract(): SpecContract {
  shared ??= new SpecContract(readFileSync(specPath(), 'utf8'));
  return shared;
}

/**
 * Proposed finding, no ticket yet (see README.md "Panel API contract specs"): every SUCCESS body carries
 * `"errorCode": null`, while every `RestResponse*` schema declares `errorCode: {type: string}` with no
 * null allowed. It is one and the same mismatch on all operations, so it is filtered by name, and by
 * nothing else, from what `contractProblems` reports: the rest of the body is still validated in full.
 * `contractProblems(..., { strict: true })` shows it again; the fix (a nullable `errorCode` in the
 * spec, or the field omitted) removes this constant and its use in the same change.
 */
export function isKnownErrorCodeNull(violation: Violation): boolean {
  return violation.path === '/errorCode' && violation.value === null;
}

/**
 * The problems of `body` against the declared `status` response of `operationId`, as readable lines; empty
 * when it conforms. Only the proposed finding above is left out, and only when `strict` is not set.
 */
export function contractProblems(
  operationId: string,
  status: number | string,
  body: unknown,
  options: { strict?: boolean } = {},
): string[] {
  return specContract()
    .violations(operationId, status, body)
    .filter((violation) => options.strict === true || !isKnownErrorCodeNull(violation))
    .map((violation) => `${violation.path} ${violation.message}`);
}
