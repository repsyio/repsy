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

import { HttpClient, HttpContext, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ToastService } from '../../panel/shared/components/toast/toast.service';
import { errorHandlerInterceptor, SILENT_ERROR } from './error-handler.interceptor';

describe('errorHandlerInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let toastService: jasmine.SpyObj<ToastService>;

  beforeEach(() => {
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorHandlerInterceptor])),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toastService },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  function fail(status: number, body: unknown = null): HttpErrorResponse {
    let caught: HttpErrorResponse | undefined;
    http.get('/api').subscribe({ error: (e) => (caught = e) });

    httpTesting.expectOne('/api').flush(body, { status, statusText: 'error' });

    expect(caught).withContext('the error is rethrown to the caller').toBeDefined();
    return caught!;
  }

  it('lets a successful response through without a toast', () => {
    let result: unknown;
    http.get('/api').subscribe((r) => (result = r));

    httpTesting.expectOne('/api').flush({ ok: true });

    expect(result).toEqual({ ok: true });
    expect(toastService.show).not.toHaveBeenCalled();
  });

  it('rethrows a 401 without a toast, so the refresh interceptor and callers handle it', () => {
    const error = fail(401, { msgId: 'sessionExpired', text: 'Session expired' });

    expect(error.status).toBe(401);
    expect(toastService.show).not.toHaveBeenCalled();
  });

  it('reports a connection error when there is no response at all', () => {
    let caught: HttpErrorResponse | undefined;
    http.get('/api').subscribe({ error: (e) => (caught = e) });

    httpTesting.expectOne('/api').error(new ProgressEvent('error'));

    expect(caught?.status).toBe(0);
    expect(toastService.show).toHaveBeenCalledOnceWith('Connection error', 'error');
  });

  it('shows the server text for a 403 and falls back to "Access denied"', () => {
    fail(403, { text: 'You may not do this' });
    expect(toastService.show).toHaveBeenCalledWith('You may not do this', 'error');

    fail(403, {});
    expect(toastService.show).toHaveBeenCalledWith('Access denied', 'error');
  });

  it('rethrows a failure of a request marked SILENT_ERROR without a toast', () => {
    let caught: HttpErrorResponse | undefined;
    http.get('/api', { context: new HttpContext().set(SILENT_ERROR, true) }).subscribe({ error: (e) => (caught = e) });

    httpTesting.expectOne('/api').flush({ msgId: 'accessDenied' }, { status: 403, statusText: 'Forbidden' });

    expect(caught?.status).toBe(403);
    expect(toastService.show).not.toHaveBeenCalled();
  });

  it('hides the server text behind a generic message for a 5xx', () => {
    fail(500, { text: 'NullPointerException at Foo.java:42' });
    expect(toastService.show).toHaveBeenCalledWith('Server error', 'error');

    fail(503);
    expect(toastService.show).toHaveBeenCalledWith('Server error', 'error');
  });

  it('shows the server text for a 503 that carries one (a lock race or a full scan queue)', () => {
    fail(503, {
      msgId: 'resourceBusy',
      text: 'The item is in use by another request. Please try again shortly.',
    });

    expect(toastService.show).toHaveBeenCalledOnceWith(
      'The item is in use by another request. Please try again shortly.',
      'error',
    );
  });

  it('shows the server text for other 4xx errors and falls back to a generic message', () => {
    fail(400, { text: 'Repository name is taken' });
    expect(toastService.show).toHaveBeenCalledWith('Repository name is taken', 'error');

    fail(404, {});
    expect(toastService.show).toHaveBeenCalledWith('An error occurred', 'error');
  });

  it('shows exactly one toast per failed request', () => {
    fail(400, { text: 'Bad input' });

    expect(toastService.show).toHaveBeenCalledTimes(1);
  });
});
