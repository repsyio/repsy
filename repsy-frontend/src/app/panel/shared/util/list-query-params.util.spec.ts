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

import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';

import { readListPageParam, readListQueryParam, updateListQueryParams } from './list-query-params.util';

function route(params: Record<string, string>): ActivatedRoute {
  return { snapshot: { queryParamMap: convertToParamMap(params) } } as ActivatedRoute;
}

describe('readListQueryParam', () => {
  it('reads a param that is present', () => {
    expect(readListQueryParam(route({ q: 'maven' }), 'q')).toBe('maven');
  });

  it('is null for a param that is absent', () => {
    expect(readListQueryParam(route({}), 'q')).toBeNull();
  });
});

describe('readListPageParam', () => {
  it('reads a page number that is present', () => {
    expect(readListPageParam(route({ page: '2' }), 'page', 0)).toBe(2);
  });

  it('falls back when the param is absent', () => {
    expect(readListPageParam(route({}), 'page', 0)).toBe(0);
  });

  it('falls back for a negative number: a hand-edited URL never pages backwards past the first', () => {
    expect(readListPageParam(route({ page: '-1' }), 'page', 0)).toBe(0);
  });

  it('falls back for a value that is not an integer', () => {
    expect(readListPageParam(route({ page: '1.5' }), 'page', 0)).toBe(0);
    expect(readListPageParam(route({ page: 'two' }), 'page', 0)).toBe(0);
  });
});

describe('updateListQueryParams', () => {
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
  });

  it('merges a changed value into the URL, replacing the current history entry', () => {
    const activatedRoute = route({ q: 'old' });

    updateListQueryParams(router, activatedRoute, { q: 'new' });

    expect(router.navigate).toHaveBeenCalledOnceWith([], {
      relativeTo: activatedRoute,
      queryParams: { q: 'new' },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  });

  it('drops a param whose patch value is null', () => {
    const activatedRoute = route({ q: 'maven' });

    updateListQueryParams(router, activatedRoute, { q: null });

    expect(router.navigate).toHaveBeenCalledOnceWith([], jasmine.objectContaining({ queryParams: { q: null } }));
  });

  it('never navigates when every value already matches the URL: safe to call on every load', () => {
    updateListQueryParams(router, route({ q: 'maven', page: '2' }), { q: 'maven', page: 2 });

    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('never navigates when the patch clears a param that was already absent', () => {
    updateListQueryParams(router, route({}), { q: null, page: null });

    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('navigates once even when only one of several values changed', () => {
    updateListQueryParams(router, route({ q: 'maven', page: '0' }), { q: 'maven', page: 1 });

    expect(router.navigate).toHaveBeenCalledTimes(1);
  });
});
