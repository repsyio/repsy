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
 * RPS-1483: the response validator of the contract specs (`src/api/spec-contract.ts`), tried on bodies that
 * are written out here, with no server. If it accepted everything, every `tests/<protocol>/panel-api.spec.ts`
 * would be green for nothing, so each way a body can be wrong has a case that must be caught, next to the
 * cases that must not be.
 */
import { operationsUnder } from '../../src/api/contract-checks.js';
import { contractProblems, specContract } from '../../src/api/spec-contract.js';
import { expect, test } from '@playwright/test';

const PAGE = { size: 10, number: 0, totalElements: 1, totalPages: 1 };
const ENVELOPE = { msgId: 'packagesFetched', type: 'SUCCESS', text: 'ok' };

function packages(item: Record<string, unknown>, extra: Record<string, unknown> = {}) {
  // A copy: the "fractional integer" test mutates `page.size`, and a shared object would hand its 1.5 to
  // every test that runs after it in the same worker.
  return { ...ENVELOPE, ...extra, data: { content: [item], page: { ...PAGE } } };
}

const ITEM = {
  name: 'a',
  stableVersion: '1.0.0',
  latestVersion: '1.0.0',
  updatedAt: '2026-09-26T11:39:58.568004Z',
};

test.describe('the response validator', () => {
  test('accepts a body that matches its schema', () => {
    expect(contractProblems('listPypiPackages', 200, packages(ITEM))).toEqual([]);
  });

  test('resolves a nested $ref, a format, a `$ref`d response and an enum', () => {
    expect(
      contractProblems(
        'listPypiPackages',
        200,
        packages({ ...ITEM, updatedAt: 'yesterday' }),
      ).join(),
    ).toContain('/data/content/0/updatedAt must match format "date-time"');
    expect(contractProblems('deleteMavenGroup', 200, { ...ENVELOPE, data: 'ARTIFACT' })).toEqual(
      [],
    );
    expect(
      contractProblems('deleteMavenGroup', 200, { ...ENVELOPE, data: 'NOTHING' }).join(),
    ).toContain('/data must be equal to one of the allowed values');
    // 404 is `#/components/responses/NotFound`: the spec's ErrorResponse, whose errorCode is a uuid.
    expect(
      contractProblems('deleteMavenGroup', 404, {
        msgId: 'x',
        type: 'ERROR',
        errorCode: '49aff165-f2b9-4f94-a85e-21f050cc4fbc',
      }),
    ).toEqual([]);
    expect(
      contractProblems('deleteMavenGroup', 404, {
        msgId: 'x',
        type: 'ERROR',
        errorCode: 'nope',
      }).join(),
    ).toContain('/errorCode must match format "uuid"');
  });

  test('catches a wrong type, a fractional integer and a string where the schema says integer', () => {
    expect(
      contractProblems('listPypiPackages', 200, packages({ ...ITEM, name: 7 })).join(),
    ).toContain('/data/content/0/name must be string');
    const body = packages(ITEM);
    (body.data.page as Record<string, unknown>).size = 1.5;
    expect(contractProblems('listPypiPackages', 200, body).join()).toContain(
      '/data/page/size must be integer',
    );
    expect(
      contractProblems('getMavenGroupSummary', 200, {
        ...ENVELOPE,
        data: { groupName: 'g', artifactCount: '1', versionCount: 1 },
      }).join(),
    ).toContain('/data/artifactCount must be integer');
  });

  test('catches a null the schema does not allow, but leaves out errorCode: null on its own', () => {
    expect(
      contractProblems('listPypiPackages', 200, packages({ ...ITEM, name: null })).join(),
    ).toContain('/data/content/0/name must be string');
    expect(contractProblems('listPypiPackages', 200, packages(ITEM, { errorCode: null }))).toEqual(
      [],
    );
    expect(
      contractProblems('listPypiPackages', 200, packages(ITEM, { errorCode: null }), {
        strict: true,
      }),
    ).toEqual(['/errorCode must be string']);
    expect(
      contractProblems('listPypiPackages', 200, packages(ITEM, { errorCode: 5 })).join(),
    ).toContain('/errorCode must be string');
    expect(
      contractProblems('listPypiPackages', 200, { ...packages(ITEM), msgId: null }).join(),
    ).toContain('/msgId must be string');
  });

  test('lists the properties the schema does not declare, at any depth', () => {
    const contract = specContract();
    expect(contract.undeclaredProperties('listPypiPackages', 200, packages(ITEM))).toEqual([]);
    expect(
      contract.undeclaredProperties(
        'listPypiPackages',
        200,
        packages({ ...ITEM, extra: 1 }, { surprise: true }),
      ),
    ).toEqual(['/surprise', '/data/content/0/extra']);
    // an object the schema leaves open (`data: {}`) is not walked
    expect(
      contract.undeclaredProperties('deletePypiPackage', 200, { ...ENVELOPE, data: { any: 1 } }),
    ).toEqual([]);
  });

  test('compiles every declared JSON response of the spec (the dialect is understood)', () => {
    const contract = specContract();
    const responses = contract
      .operationIds()
      .flatMap((operationId) =>
        contract.statusesOf(operationId).map((status) => ({ operationId, status })),
      )
      .filter(({ operationId, status }) => contract.hasJsonBody(operationId, status));
    const failures = responses.flatMap(({ operationId, status }) => {
      try {
        contract.violations(operationId, status, {});
        return [];
      } catch (error) {
        return [`${operationId} ${status}: ${String(error)}`];
      }
    });
    expect(failures, 'responses whose schema does not compile').toEqual([]);
    expect(responses.length, 'declared JSON responses').toBeGreaterThan(400);
  });

  test('finds the operations of a protocol by path, without its key-store routes', () => {
    const maven = operationsUnder('/api/mvn/artifacts', '/api/mvn/groups');
    expect(maven).toContain('getMavenGroupSummary');
    expect(maven).toContain('deleteMavenArtifactVersion');
    expect(maven).not.toContain('listMavenKeyStores');
    expect(operationsUnder('/api/npm/')).toContain('listNpmPackageTags');
    expect(operationsUnder('/api/pypi/')).toContain('deletePypiRelease');
  });
});
