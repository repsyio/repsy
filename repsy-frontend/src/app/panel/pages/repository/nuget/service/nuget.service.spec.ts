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

import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../../../../../environments/environment';
import { DeployTokenForm, RepoCreateForm, RepoDescriptionForm, RepoRenameForm } from '../../../../../../generated/api';
import { ErrorHandlerService } from '../../../../../shared/error-handler/error-handler.service';
import { RepoSettingsForm } from '../../../../shared/dto/repo/repo-settings-form';
import { Sort } from '../../../../shared/dto/sort';
import { collect, permission, REPO, restResponse } from '../../testing/protocol-service-spec-helpers';
import { NugetService } from './nuget.service';

const API = environment.apiBaseUrl;
const HANDLED = 'the text the error handler produced';
const SORT: Sort = { name: 'Name', column: 'downloads', type: 'ASC' };
const PACKAGE = 'Acme.Widget';
const VERSION = '1.2.3';
const REPO_URL = `${API}/api/repos/${REPO}`;
const PACKAGE_URL = `${API}/api/nuget/packages/${REPO}`;

function asForm<T>(value: object): T {
  return value as unknown as T;
}

const CREATE_FORM = asForm<RepoCreateForm>({ name: 'new-repo' });
const SETTINGS_FORM = asForm<RepoSettingsForm>({ allowOverride: true });
const RENAME_FORM = asForm<RepoRenameForm>({ name: 'renamed-repo' });
const DESCRIPTION_FORM = asForm<RepoDescriptionForm>({ description: 'a description' });
const TOKEN_FORM = asForm<DeployTokenForm>({ name: 'ci' });

/** One service method, the request it must send, and what it must resolve with. */
interface Case {
  name: string;
  /** False for the methods that do not read the active repository. */
  needsRepo: boolean;
  call: (service: NugetService) => Promise<unknown>;
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  url: string;
  body?: unknown;
  /** The complete query string; an absent entry means no query parameters at all. */
  params?: Record<string, string>;
  /** The `data` of the response envelope. */
  data?: unknown;
  /** What the promise resolves with; defaults to `data`. */
  expected?: unknown;
}

const CASES: Case[] = [
  {
    name: 'createRepository',
    needsRepo: false,
    call: (s) => s.createRepository(CREATE_FORM),
    method: 'POST',
    url: `${API}/api/repos/NUGET`,
    body: CREATE_FORM,
    data: { ignored: true },
    expected: undefined,
  },
  {
    name: 'fetchRepositories',
    needsRepo: false,
    call: (s) => s.fetchRepositories(),
    method: 'GET',
    url: `${API}/api/repos/NUGET/info`,
    data: [{ repoName: 'a' }],
  },
  {
    name: 'deleteRepository',
    needsRepo: false,
    call: (s) => s.deleteRepository('some-other-repo'),
    method: 'DELETE',
    url: `${API}/api/repos/some-other-repo`,
    data: { ignored: true },
    expected: undefined,
  },
  {
    name: 'fetchRepositoryUsage',
    needsRepo: true,
    call: (s) => s.fetchRepositoryUsage(),
    method: 'GET',
    url: `${REPO_URL}/usage`,
    data: { usedBytes: 12 },
  },
  {
    name: 'fetchRepositorySettings',
    needsRepo: true,
    call: (s) => s.fetchRepositorySettings(),
    method: 'GET',
    url: `${REPO_URL}/settings`,
    data: SETTINGS_FORM,
  },
  {
    name: 'updateRepoSettings',
    needsRepo: true,
    call: (s) => s.updateRepoSettings(SETTINGS_FORM),
    method: 'PUT',
    url: `${REPO_URL}/settings`,
    body: SETTINGS_FORM,
    data: { ignored: true },
    expected: undefined,
  },
  {
    name: 'updateRepositoryName',
    needsRepo: true,
    call: (s) => s.updateRepositoryName(RENAME_FORM),
    method: 'PATCH',
    url: `${REPO_URL}/name`,
    body: RENAME_FORM,
    data: { ignored: true },
    expected: undefined,
  },
  {
    name: 'updateRepoDescription',
    needsRepo: true,
    call: (s) => s.updateRepoDescription(DESCRIPTION_FORM),
    method: 'PATCH',
    url: `${REPO_URL}/description`,
    body: DESCRIPTION_FORM,
    data: { ignored: true },
    expected: undefined,
  },
  {
    name: 'fetchRepositoryPackages',
    needsRepo: true,
    call: (s) => s.fetchRepositoryPackages('widg', SORT, 2, 25),
    method: 'GET',
    url: PACKAGE_URL,
    params: { query: 'widg', page: '2', sort: 'downloads,ASC', size: '25' },
    data: { content: [{ packageId: PACKAGE }], page: { number: 2 } },
  },
  {
    name: 'fetchRepositoryPackages with an empty query',
    needsRepo: true,
    call: (s) => s.fetchRepositoryPackages('', SORT, 0, 10),
    method: 'GET',
    url: PACKAGE_URL,
    // The empty query is sent as `query=`, unlike the generated-client services that send undefined.
    params: { query: '', page: '0', sort: 'downloads,ASC', size: '10' },
    data: { content: [], page: { number: 0 } },
  },
  {
    name: 'fetchPackage',
    needsRepo: true,
    call: (s) => s.fetchPackage(PACKAGE),
    method: 'GET',
    url: `${PACKAGE_URL}/${PACKAGE}`,
    data: { packageId: PACKAGE },
  },
  {
    name: 'fetchPackageVersions',
    needsRepo: true,
    call: (s) => s.fetchPackageVersions(PACKAGE, SORT, 1, 50),
    method: 'GET',
    url: `${PACKAGE_URL}/${PACKAGE}/versions`,
    params: { page: '1', sort: 'downloads,ASC', size: '50' },
    data: { content: [{ version: VERSION }], page: { number: 1 } },
  },
  {
    name: 'fetchPackageVersion',
    needsRepo: true,
    call: (s) => s.fetchPackageVersion(PACKAGE, VERSION),
    method: 'GET',
    url: `${PACKAGE_URL}/${PACKAGE}/${VERSION}`,
    data: { version: VERSION },
  },
  {
    name: 'deletePackage',
    needsRepo: true,
    call: (s) => s.deletePackage(PACKAGE),
    method: 'DELETE',
    url: `${PACKAGE_URL}/${PACKAGE}`,
    data: { deletedVersionCount: 3 },
  },
  {
    name: 'deletePackageVersion',
    needsRepo: true,
    call: (s) => s.deletePackageVersion(PACKAGE, VERSION),
    method: 'DELETE',
    url: `${PACKAGE_URL}/${PACKAGE}/${VERSION}`,
    data: { deletedVersionCount: 1 },
  },
  {
    name: 'getDeployTokens',
    needsRepo: true,
    call: (s) => s.getDeployTokens(3, 20),
    method: 'GET',
    url: `${REPO_URL}/deploy-tokens`,
    params: { page: '3', size: '20' },
    data: { content: [{ id: 't1' }], page: { number: 3 } },
  },
  {
    name: 'rotateDeployToken',
    needsRepo: true,
    call: (s) => s.rotateDeployToken('t1'),
    method: 'PUT',
    url: `${REPO_URL}/deploy-tokens/t1`,
    body: {},
    data: 'new-secret',
  },
  {
    name: 'createDeployToken',
    needsRepo: true,
    call: (s) => s.createDeployToken(TOKEN_FORM),
    method: 'POST',
    url: `${REPO_URL}/deploy-tokens`,
    body: TOKEN_FORM,
    data: { id: 't2', secret: 'secret' },
  },
  {
    name: 'revokeDeployToken',
    needsRepo: true,
    call: (s) => s.revokeDeployToken('t1'),
    method: 'DELETE',
    url: `${REPO_URL}/deploy-tokens/t1`,
    data: { ignored: true },
    expected: undefined,
  },
];

describe('NugetService', () => {
  let service: NugetService;
  let http: HttpTestingController;
  let handle: jasmine.Spy;

  beforeEach(() => {
    handle = jasmine.createSpy('handle').and.returnValue(HANDLED);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ErrorHandlerService, useValue: { handle } },
      ],
    });
    service = TestBed.inject(NugetService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function expectRequest(method: string, url: string): TestRequest {
    return http.expectOne((req) => req.method === method && req.url === url, `${method} ${url}`);
  }

  async function activate(repoName = REPO): Promise<void> {
    const done = firstValueFrom(service.selectRepository(repoName));
    expectRequest('GET', `${API}/api/repos/${repoName}/permissions`).flush(restResponse(permission(repoName)));
    await done;
  }

  describe('selectRepository', () => {
    it('loads the permission, emits it and completes', async () => {
      const info = permission(REPO, { canWrite: true });
      const emitted: unknown[] = [];
      let completed = false;
      service.selectRepository(REPO).subscribe({ next: (v) => emitted.push(v), complete: () => (completed = true) });

      expectRequest('GET', `${API}/api/repos/${REPO}/permissions`).flush(restResponse(info));

      expect(emitted).toEqual([info]);
      expect(completed).toBeTrue();
    });

    it('pushes the permission on repoChanges, starting from null', async () => {
      const emissions = collect(service.repoChanges);
      expect(emissions).toEqual([null]);

      await activate();

      expect(emissions as unknown[]).toEqual([null, permission(REPO)]);
    });

    it('makes the repository the active one for the calls that follow', async () => {
      await activate();

      const done = service.fetchPackage(PACKAGE);
      expectRequest('GET', `${PACKAGE_URL}/${PACKAGE}`).flush(restResponse({}));
      await done;
    });

    it('errors with the error handler result and activates nothing', async () => {
      const emissions = collect(service.repoChanges);
      const result = firstValueFrom(service.selectRepository(REPO));

      expectRequest('GET', `${REPO_URL}/permissions`).flush({}, { status: 404, statusText: 'Not Found' });

      await expectAsync(result).toBeRejectedWith(HANDLED);
      expect(handle).toHaveBeenCalledOnceWith(jasmine.any(HttpErrorResponse));
      expect(handle.calls.mostRecent().args[0].status).toBe(404);
      expect(emissions).toEqual([null]);
    });
  });

  describe('updateRepositoryName', () => {
    it('rewrites the active repository name and re-emits it after the rename succeeds', async () => {
      await activate();
      const emissions = collect(service.repoChanges);

      const done = service.updateRepositoryName(RENAME_FORM);
      expectRequest('PATCH', `${REPO_URL}/name`).flush(restResponse(null));
      await done;

      expect(emissions.length).toBe(2);
      expect(emissions.at(-1).repoName).toBe('renamed-repo');
    });

    it('sends the calls that follow to the new name', async () => {
      await activate();

      const rename = service.updateRepositoryName(RENAME_FORM);
      expectRequest('PATCH', `${REPO_URL}/name`).flush(restResponse(null));
      await rename;
      const next = service.fetchRepositoryUsage();
      expectRequest('GET', `${API}/api/repos/renamed-repo/usage`).flush(restResponse({}));
      await next;
    });

    it('keeps the old name and emits nothing when the rename fails', async () => {
      await activate();
      const emissions = collect(service.repoChanges);

      const done = service.updateRepositoryName(RENAME_FORM);
      expectRequest('PATCH', `${REPO_URL}/name`).flush({}, { status: 409, statusText: 'Conflict' });
      await expectAsync(done).toBeRejectedWith(HANDLED);

      expect(emissions.length).toBe(1);
      const usage = service.fetchRepositoryUsage();
      expectRequest('GET', `${REPO_URL}/usage`).flush(restResponse({}));
      await usage;
    });
  });

  describe('requests', () => {
    for (const c of CASES) {
      describe(c.name, () => {
        beforeEach(async () => {
          if (c.needsRepo) {
            await activate();
          }
        });

        it(`sends ${c.method} ${c.url.replace(API, '')} and resolves with the unwrapped data`, async () => {
          const result = c.call(service);

          const req = expectRequest(c.method, c.url);
          if ('body' in c) {
            expect(req.request.body).toEqual(c.body);
          }
          expect(req.request.params.keys().sort()).toEqual(Object.keys(c.params ?? {}).sort());
          for (const [key, value] of Object.entries(c.params ?? {})) {
            expect(req.request.params.get(key)).withContext(`query parameter ${key}`).toBe(value);
          }
          req.flush(restResponse(c.data));

          expect(await result).toEqual('expected' in c ? c.expected : c.data);
        });

        it('rejects with what the error handler returns', async () => {
          const result = c.call(service);

          expectRequest(c.method, c.url).flush({ text: 'nope' }, { status: 500, statusText: 'Server Error' });

          await expectAsync(result).toBeRejectedWith(HANDLED);
          expect(handle).toHaveBeenCalledOnceWith(jasmine.any(HttpErrorResponse));
          expect(handle.calls.mostRecent().args[0].status).toBe(500);
        });
      });
    }
  });

  describe('before a repository is selected', () => {
    // Current behaviour, pinned on purpose (RPS-1160): these methods dereference activeRepo without a guard, so they
    // reject with a TypeError instead of a meaningful error, and no request is sent.
    for (const c of CASES.filter((it) => it.needsRepo)) {
      it(`${c.name} rejects with a TypeError`, async () => {
        await expectAsync(c.call(service)).toBeRejectedWithError(TypeError);
      });
    }

    for (const c of CASES.filter((it) => !it.needsRepo)) {
      it(`${c.name} still works`, async () => {
        const result = c.call(service);

        expectRequest(c.method, c.url).flush(restResponse(c.data));

        expect(await result).toEqual('expected' in c ? c.expected : c.data);
      });
    }
  });
});
