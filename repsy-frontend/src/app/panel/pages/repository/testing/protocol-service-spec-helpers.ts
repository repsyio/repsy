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

// Test-only helpers shared by the per-protocol service specs. This file is not a spec, and nothing under src/app
// imports it, so it is never part of the application bundle: tsconfig.app.json compiles only what main.ts reaches,
// tsconfig.spec.json reaches it through the specs.

import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, NEVER, Observable, of, throwError } from 'rxjs';

import { PageMetadata, RepoPermissionInfo } from '../../../../../generated/api';
import { PagedData } from '../../../shared/dto/paged-data';
import { Sort } from '../../../shared/dto/sort';

export const REPO = 'acme-repo';
export const SORT: Sort = { name: 'Name', column: 'name', type: 'DESC' };
export const PAGE_INDEX = 2;
export const PAGE_SIZE = 25;
/**
 * The trailing `page`, `size`, `sort` arguments, in that order, the services must pass to a generated list method,
 * built from {@link SORT}, {@link PAGE_INDEX} and {@link PAGE_SIZE}. They follow the path parameters and the filters
 * (`q`, `scope`), so a spec spreads them last: `[REPO, search, ...PAGE_ARGS]`.
 */
export const PAGE_ARGS: readonly unknown[] = [PAGE_INDEX, PAGE_SIZE, ['name,DESC']];
export const PAGE_METADATA: PageMetadata = { number: PAGE_INDEX, size: PAGE_SIZE, totalElements: 60, totalPages: 3 };

/** The `RestResponse*` envelope of the generated client. */
export function restResponse<T>(data: T): { data: T } {
  return { data };
}

/** The `RestResponsePagedModel*` envelope of the generated client. */
export function pagedResponse<T>(
  content: T[],
  page: PageMetadata = PAGE_METADATA,
): { data: { content: T[]; page: PageMetadata } } {
  return restResponse({ content, page });
}

export function permission(
  repoName: string,
  flags: Partial<Pick<RepoPermissionInfo, 'canRead' | 'canWrite' | 'canManage' | 'private' | 'description'>> = {},
): RepoPermissionInfo {
  return { repoName, canRead: true, canWrite: false, canManage: false, private: false, ...flags };
}

/** Asserts the `{ content, page }` remap the services apply to every generated paged model. */
export function expectPaged<T>(actual: PagedData<T>, content: T[], page: PageMetadata | undefined): void {
  expect(actual.content).toEqual(content);
  expect(actual.page as unknown).toEqual(page);
}

export function httpError(status = 500): HttpErrorResponse {
  return new HttpErrorResponse({ status, statusText: 'error', error: { text: 'boom' } });
}

/** The two names a protocol service uses for "load and activate a repository" (`selectRepository` on PyPI). */
export interface RepoSelecting {
  readonly repoChanges: Observable<RepoPermissionInfo>;
  getRepository?(repoName: string): Observable<RepoPermissionInfo>;
  selectRepository?(repoName: string): Observable<RepoPermissionInfo>;
}

function select(service: RepoSelecting, repoName: string): Observable<RepoPermissionInfo> {
  const method = service.getRepository ?? service.selectRepository;
  return method.call(service, repoName);
}

/**
 * Drives `getRepository`/`selectRepository` to completion against `getPermission`, so the calls that follow see
 * `repoName` as the active repository.
 */
export function selectRepo(
  service: RepoSelecting,
  getPermission: jasmine.Spy,
  repoName: string,
  flags?: Parameters<typeof permission>[1],
): Promise<RepoPermissionInfo> {
  getPermission.and.returnValue(of(restResponse(permission(repoName, flags))));
  return firstValueFrom(select(service, repoName));
}

/** Collects everything `observable` emits from now on; the array fills as values arrive. */
export function collect<T>(observable: Observable<T>): T[] {
  const values: T[] = [];
  observable.subscribe((value) => values.push(value));
  return values;
}

export interface CallCase<S> {
  name: string;
  invoke: (service: S) => Observable<unknown>;
  /** The generated-client method the call must reach. */
  api: () => jasmine.Spy;
  /** The exact arguments, in order, the generated-client method must receive. */
  args: unknown[];
  /** What the generated-client method answers with. */
  response: unknown;
  /** What the service must emit. */
  expected: unknown;
  /** Sibling client methods that must stay untouched (the other branch of a scoped/unscoped split). */
  notCalled?: () => jasmine.Spy[];
}

/** Registers, per case, that the arguments and response mapping are right and that client errors pass through. */
export function describeCalls<S>(service: () => S, cases: CallCase<S>[]): void {
  for (const c of cases) {
    describe(c.name, () => {
      it('forwards the arguments in order and maps the response', async () => {
        c.api().and.returnValue(of(c.response));

        const result = await firstValueFrom(c.invoke(service()));

        expect(c.api()).toHaveBeenCalledOnceWith(...c.args);
        expect(result).toEqual(c.expected);
        (c.notCalled?.() ?? []).forEach((other) => expect(other).not.toHaveBeenCalled());
      });

      it('lets a client error through unchanged', async () => {
        const error = httpError();
        c.api().and.returnValue(throwError(() => error));

        await expectAsync(firstValueFrom(c.invoke(service()))).toBeRejectedWith(error);
      });
    });
  }
}

export interface PagedCase<S> {
  name: string;
  invoke: (service: S, term: string) => Observable<PagedData<unknown>>;
  api: () => jasmine.Spy;
  /** The exact arguments the generated-client method must receive; `term` is `undefined` for an empty search. */
  args: (term?: string) => unknown[];
  /** False for a listing without a search term (Go `fetchModules`). */
  searchable?: boolean;
  notCalled?: () => jasmine.Spy[];
}

/** Registers the paging contract of a list call: search fallback, argument order and the `{ content, page }` remap. */
export function describePagedCalls<S>(service: () => S, cases: PagedCase<S>[]): void {
  const item = { id: 'item-1' };

  for (const c of cases) {
    describe(c.name, () => {
      const searchable = c.searchable ?? true;
      const term = searchable ? 'needle' : undefined;

      it('passes the sort, the page and the term to the client in order and remaps the page', async () => {
        c.api().and.returnValue(of(pagedResponse([item])));

        const result = await firstValueFrom(c.invoke(service(), term));

        expect(c.api()).toHaveBeenCalledOnceWith(...c.args(term));
        expectPaged(result, [item], PAGE_METADATA);
        (c.notCalled?.() ?? []).forEach((other) => expect(other).not.toHaveBeenCalled());
      });

      if (searchable) {
        it('sends undefined instead of an empty search term', async () => {
          c.api().and.returnValue(of(pagedResponse([item])));

          await firstValueFrom(c.invoke(service(), ''));

          expect(c.api()).toHaveBeenCalledOnceWith(...c.args(undefined));
        });
      }

      it('falls back to an empty content list when the response has no content', async () => {
        c.api().and.returnValue(of(restResponse({ page: PAGE_METADATA })));

        expectPaged(await firstValueFrom(c.invoke(service(), term)), [], PAGE_METADATA);
      });

      it('falls back to an empty page when the response has no data', async () => {
        c.api().and.returnValue(of(restResponse(undefined)));

        expectPaged(await firstValueFrom(c.invoke(service(), term)), [], undefined);
      });

      it('lets a client error through unchanged', async () => {
        const error = httpError(403);
        c.api().and.returnValue(throwError(() => error));

        await expectAsync(firstValueFrom(c.invoke(service(), term))).toBeRejectedWith(error);
      });
    });
  }
}

export interface RepoSelectionOptions<S extends RepoSelecting> {
  service: () => S;
  getPermission: () => jasmine.Spy;
  /** Any service call that sends the active repository name to the generated client. */
  probe: (service: S) => Observable<unknown>;
  probeApi: () => jasmine.Spy;
  /** Position of the repository name in the arguments of `probeApi`. */
  probeRepoArg: number;
  /**
   * True when the service clears the active repository as soon as a different one is requested
   * (`resetActiveRepoIfChanged`). False pins the services that lack it: they keep using the previous repository's
   * name until the new permission arrives.
   */
  resetsOnChange: boolean;
}

/** Registers the repository-selection contract shared by the generated-client services. */
export function describeRepoSelection<S extends RepoSelecting>(options: RepoSelectionOptions<S>): void {
  const { service, getPermission, probeApi } = options;

  function repoNameSentBy(call: (service: S) => Observable<unknown>): string {
    probeApi().and.returnValue(of(restResponse(null)));
    call(service());
    return probeApi().calls.mostRecent().args[options.probeRepoArg] as string;
  }

  describe('repository selection', () => {
    it('loads the permission of the requested repository and emits it unwrapped', async () => {
      const result = await selectRepo(service(), getPermission(), REPO, { canWrite: true });

      expect(getPermission()).toHaveBeenCalledOnceWith(REPO);
      expect(result).toEqual(permission(REPO, { canWrite: true }));
    });

    it('publishes the loaded permission on repoChanges', async () => {
      const emissions = collect(service().repoChanges);
      expect(emissions).withContext('a BehaviorSubject starts without an active repository').toEqual([null]);

      await selectRepo(service(), getPermission(), REPO, { canManage: true });

      expect(emissions.at(-1)).toEqual(permission(REPO, { canManage: true }));
    });

    it('sends an empty repository name until a repository is selected', () => {
      expect(repoNameSentBy(options.probe)).toBe('');
    });

    it('sends the selected repository name afterwards', async () => {
      await selectRepo(service(), getPermission(), REPO);

      expect(repoNameSentBy(options.probe)).toBe(REPO);
    });

    it('lets a permission error through without activating the repository', async () => {
      const error = httpError(404);
      const emissions = collect(service().repoChanges);
      getPermission().and.returnValue(throwError(() => error));

      await expectAsync(firstValueFrom(select(service(), REPO))).toBeRejectedWith(error);

      expect(emissions.filter((value) => value !== null)).toEqual([]);
    });

    if (options.resetsOnChange) {
      it('clears the active repository as soon as a different one is requested', async () => {
        await selectRepo(service(), getPermission(), REPO);
        const emissions = collect(service().repoChanges);
        getPermission().and.returnValue(NEVER);

        select(service(), 'other-repo');

        expect(emissions.at(-1)).withContext('the switch publishes null before the answer arrives').toBeNull();
        expect(repoNameSentBy(options.probe)).toBe('');
      });

      it('keeps the active repository when the same one is requested again', async () => {
        await selectRepo(service(), getPermission(), REPO);
        const emissions = collect(service().repoChanges);
        getPermission().and.returnValue(NEVER);

        select(service(), REPO);

        expect(emissions.length).withContext('no emission besides the replayed current value').toBe(1);
        expect(repoNameSentBy(options.probe)).toBe(REPO);
      });
    } else {
      // Current behaviour, pinned on purpose: this service has no resetActiveRepoIfChanged, so after switching
      // repositories the first calls still carry the previous repository's name (RPS-1159).
      it('keeps the previous repository active until the new permission arrives (no reset on change)', async () => {
        await selectRepo(service(), getPermission(), REPO);
        const emissions = collect(service().repoChanges);
        getPermission().and.returnValue(NEVER);

        select(service(), 'other-repo');

        expect(emissions.length).withContext('nothing is published for the switch').toBe(1);
        expect(emissions.at(-1)?.repoName).toBe(REPO);
        expect(repoNameSentBy(options.probe)).toBe(REPO);
      });
    }
  });
}
