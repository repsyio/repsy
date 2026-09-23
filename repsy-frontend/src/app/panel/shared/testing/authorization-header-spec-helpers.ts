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

// Test-only helper shared by the specs of the panel services (profile, user, usage, security). The `Authorization`
// header is set once by `HttpHeadersInterceptor`, so a service must not build or pass one itself. This file is not a
// spec, and nothing under src/app imports it, so it is never part of the application bundle.

import { firstValueFrom, Observable, of } from 'rxjs';

import { restResponse } from '../../pages/repository/testing/protocol-service-spec-helpers';

export interface NoAuthorizationHeaderOptions {
  /** The generated-client method the call reaches. */
  api: () => jasmine.Spy;
  /** Any call of the service that reaches {@link api}. */
  invoke: () => Observable<unknown>;
}

/** Registers that the service hands the generated client no `Authorization` value, whatever the session state. */
export function describeNoAuthorizationHeader(options: NoAuthorizationHeaderOptions): void {
  const { api, invoke } = options;

  describe('authorization header', () => {
    it('is not passed to the generated client, the http-headers interceptor sets it', async () => {
      api().and.returnValue(of(restResponse({})));

      await firstValueFrom(invoke());

      const args: unknown[] = api().calls.mostRecent().args;
      expect(args.filter((a) => typeof a === 'string' && /^bearer\b/i.test(a))).toEqual([]);
    });
  });
}
