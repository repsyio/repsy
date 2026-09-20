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

import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';

import { ErrorHandlerService } from './error-handler.service';

describe('ErrorHandlerService', () => {
  let router: jasmine.SpyObj<Router>;
  let clearStorage: jasmine.Spy;
  let service: ErrorHandlerService;

  beforeEach(() => {
    router = jasmine.createSpyObj<Router>('Router', ['navigateByUrl']);
    clearStorage = spyOn(Storage.prototype, 'clear');
    spyOn(console, 'error');
    service = new ErrorHandlerService(router);
  });

  function error(status: number, body: unknown): HttpErrorResponse {
    return new HttpErrorResponse({ status, error: body });
  }

  it('reports the service as unavailable when there is no response', () => {
    expect(service.handle(undefined as unknown as HttpErrorResponse)).toBe('Service unavailable');
    expect(service.handle(error(0, {}))).toBe('Service unavailable');
    expect(console.error).not.toHaveBeenCalled();
  });

  it('returns the text the server sent', () => {
    expect(service.handle(error(400, { text: 'Repository name is taken' }))).toBe('Repository name is taken');
  });

  it('falls back to a generic text when the server sent none', () => {
    expect(service.handle(error(500, { msgId: 'boom' }))).toBe('Error Occurred');
  });

  it('logs the error body for anything but an expired session', () => {
    const body = { msgId: 'invalidCredentials', text: 'Nope' };

    service.handle(error(401, body));

    expect(console.error).toHaveBeenCalledOnceWith(body);
    expect(clearStorage).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  ['sessionExpired', 'refreshTokenExpired'].forEach((msgId) => {
    it(`clears the stored session and goes to the root on a 401 ${msgId}`, () => {
      const text = service.handle(error(401, { msgId, text: 'Please sign in again' }));

      expect(clearStorage).toHaveBeenCalledTimes(1);
      expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/');
      expect(text).toBe('Please sign in again');
      expect(console.error).not.toHaveBeenCalled();
    });
  });

  it('does not treat an expired-session msgId on another status as a sign-out', () => {
    service.handle(error(403, { msgId: 'sessionExpired' }));

    expect(clearStorage).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
