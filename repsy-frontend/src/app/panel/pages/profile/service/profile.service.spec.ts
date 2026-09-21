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

import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';

import { LoginInfo, ProfileControllerService, ProfileInfo } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import {
  describeAuthorizationHeader,
  FakeAuthService,
  fakeAuthService,
} from '../../../shared/testing/authorization-header-spec-helpers';
import {
  CallCase,
  describeCalls,
  httpError,
  restResponse,
} from '../../repository/testing/protocol-service-spec-helpers';
import { ProfileService } from './profile.service';

const TOKEN = 'access-token';
const BEARER = `Bearer ${TOKEN}`;
const PROFILE: ProfileInfo = {
  id: 'user-1',
  username: 'alice',
  role: 'USER',
  diskUsage: 1024,
  createdAt: '2026-01-01T00:00:00Z',
  lastLoginAt: '2026-02-01T00:00:00Z',
};
const LOGIN_INFO: LoginInfo = { username: 'alice', token: 'new-token', refreshToken: 'new-refresh-token' };

describe('ProfileService', () => {
  let api: jasmine.SpyObj<ProfileControllerService>;
  let authService: FakeAuthService;
  let service: ProfileService;

  beforeEach(() => {
    api = jasmine.createSpyObj<ProfileControllerService>('ProfileControllerService', [
      'getProfile',
      'updatePassword',
      'updateUsername',
      'deleteProfile',
    ]);
    authService = fakeAuthService(TOKEN);
    TestBed.configureTestingModule({
      providers: [
        { provide: ProfileControllerService, useValue: api },
        { provide: AuthService, useValue: authService },
      ],
    });
    service = TestBed.inject(ProfileService);
  });

  const cases: CallCase<ProfileService>[] = [
    {
      name: 'get',
      invoke: (s) => s.get(),
      api: () => api.getProfile,
      args: [BEARER],
      response: restResponse(PROFILE),
      expected: PROFILE,
    },
    {
      name: 'updatePassword',
      invoke: (s) => s.updatePassword('n3w-secret'),
      api: () => api.updatePassword,
      args: [BEARER, { password: 'n3w-secret' }],
      response: restResponse(LOGIN_INFO),
      expected: LOGIN_INFO,
    },
    {
      name: 'updateUsername',
      invoke: (s) => s.updateUsername('bob'),
      api: () => api.updateUsername,
      args: [BEARER, { username: 'bob' }],
      response: restResponse(LOGIN_INFO),
      expected: LOGIN_INFO,
    },
    {
      name: 'deleteAccount',
      invoke: (s) => s.deleteAccount(),
      api: () => api.deleteProfile,
      args: [BEARER],
      response: restResponse(undefined),
      expected: undefined,
    },
  ];
  describeCalls(() => service, cases);

  describeAuthorizationHeader({
    authService: () => authService,
    api: () => api.getProfile,
    invoke: () => service.get(),
  });

  describe('updatePassword', () => {
    it('stores the new login info in the auth service once the call succeeds', async () => {
      (api.updatePassword as jasmine.Spy).and.returnValue(of(restResponse(LOGIN_INFO)));

      const result = service.updatePassword('n3w-secret');
      expect(authService.updateLoginInfo).withContext('nothing happens before subscription').not.toHaveBeenCalled();
      await firstValueFrom(result);

      expect(authService.updateLoginInfo).toHaveBeenCalledOnceWith(LOGIN_INFO);
    });

    it('keeps the old session when the call fails', async () => {
      (api.updatePassword as jasmine.Spy).and.returnValue(throwError(() => httpError(400)));

      await expectAsync(firstValueFrom(service.updatePassword('short'))).toBeRejected();

      expect(authService.updateLoginInfo).not.toHaveBeenCalled();
    });
  });

  describe('updateUsername', () => {
    // Current behaviour, pinned on purpose: unlike updatePassword the service does not refresh the auth service; the
    // caller (AccountInfoComponent) writes the new login info to localStorage itself and reloads the page.
    it('does not touch the auth service', async () => {
      (api.updateUsername as jasmine.Spy).and.returnValue(of(restResponse(LOGIN_INFO)));

      await firstValueFrom(service.updateUsername('bob'));

      expect(authService.updateLoginInfo).not.toHaveBeenCalled();
    });
  });
});
