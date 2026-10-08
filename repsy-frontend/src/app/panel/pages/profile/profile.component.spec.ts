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

import { of, throwError } from 'rxjs';

import { AccessTokenListItem, AccessTokensApi } from '../../../../generated/api';
import { ProfileComponent } from './profile.component';

describe('ProfileComponent', () => {
  const item = (expirationDate: string): AccessTokenListItem =>
    ({
      id: expirationDate,
      name: expirationDate,
      scopes: [],
      createdAt: '2026-01-01T00:00:00Z',
      expirationDate,
    }) as never;

  function profile(api: jasmine.SpyObj<AccessTokensApi>): ProfileComponent {
    const component = new ProfileComponent(api);
    component.ngOnInit();
    return component;
  }

  it('counts only the access tokens that have not expired, for the password warning', () => {
    const api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', ['listAccessTokens']);
    api.listAccessTokens.and.returnValue(
      of({
        content: [item('2999-01-01T00:00:00Z'), item('2999-02-01T00:00:00Z'), item('2000-01-01T00:00:00Z')],
      }) as never,
    );

    expect(profile(api).liveAccessTokens).toBe(2);
    expect(api.listAccessTokens).toHaveBeenCalledWith(0, 100, ['expirationDate,desc']);
  });

  it('shows no warning when the list cannot be read', () => {
    const api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', ['listAccessTokens']);
    api.listAccessTokens.and.returnValue(throwError(() => new Error('down')) as never);

    expect(profile(api).liveAccessTokens).toBe(0);
  });
});
