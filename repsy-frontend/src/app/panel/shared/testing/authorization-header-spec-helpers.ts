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

// Test-only helper shared by the specs of the services that build an `Authorization` header from `AuthService`
// (profile, user, usage, security). This file is not a spec, and nothing under src/app imports it, so it is never
// part of the application bundle.

import { firstValueFrom, Observable, of } from 'rxjs';

import { restResponse } from '../../pages/repository/testing/protocol-service-spec-helpers';

/** The slice of `AuthService` those services read: the token, which the spec changes between calls. */
export interface FakeAuthService {
  accessToken: string | null;
  updateLoginInfo: jasmine.Spy;
}

export function fakeAuthService(accessToken: string | null = 'access-token'): FakeAuthService {
  return { accessToken, updateLoginInfo: jasmine.createSpy('updateLoginInfo') };
}

export interface AuthorizationHeaderOptions {
  authService: () => FakeAuthService;
  /** The generated-client method that takes the header as its first argument. */
  api: () => jasmine.Spy;
  /** Any call of the service that reaches {@link api}. */
  invoke: () => Observable<unknown>;
}

/** Registers how the header is built: from the token at call time, and what it becomes without a token. */
export function describeAuthorizationHeader(options: AuthorizationHeaderOptions): void {
  const { authService, api, invoke } = options;

  async function headerOfNextCall(): Promise<unknown> {
    api().calls.reset();
    api().and.returnValue(of(restResponse({})));
    await firstValueFrom(invoke());
    return api().calls.mostRecent().args[0];
  }

  describe('authorization header', () => {
    it('is the access token as a bearer credential', async () => {
      authService().accessToken = 'token-1';

      expect(await headerOfNextCall()).toBe('Bearer token-1');
    });

    it('reads the access token again on every call', async () => {
      authService().accessToken = 'token-1';
      expect(await headerOfNextCall()).toBe('Bearer token-1');

      authService().accessToken = 'token-2';

      expect(await headerOfNextCall()).toBe('Bearer token-2');
    });

    // Current behaviour, pinned on purpose: a signed-out call sends the literal "Bearer null" instead of leaving the
    // header out (the http-headers interceptor already sets it when a session exists). RPS-1161 tracks the fix.
    it('is the literal "Bearer null" while signed out (pinned defect, RPS-1161)', async () => {
      authService().accessToken = null;

      expect(await headerOfNextCall()).toBe('Bearer null');
    });
  });
}
