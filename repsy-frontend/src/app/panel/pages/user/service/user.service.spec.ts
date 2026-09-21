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

import {
  PagedModelUserResponse,
  UserControllerService,
  UserCreateForm,
  UserResponse,
  UserUpdateForm,
} from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import {
  describeAuthorizationHeader,
  FakeAuthService,
  fakeAuthService,
} from '../../../shared/testing/authorization-header-spec-helpers';
import { CallCase, describeCalls, restResponse } from '../../repository/testing/protocol-service-spec-helpers';
import { UserService } from './user.service';

const TOKEN = 'access-token';
const BEARER = `Bearer ${TOKEN}`;
const USER: UserResponse = {
  id: 'user-1',
  username: 'alice',
  role: 'USER',
  createdAt: '2026-01-01T00:00:00Z',
  lastLoginAt: '2026-02-01T00:00:00Z',
};
const PAGE: PagedModelUserResponse = {
  content: [USER],
  page: { number: 1, size: 10, totalElements: 11, totalPages: 2 },
};
const CREATE_FORM: UserCreateForm = { username: 'bob', password: 'secret', role: 'ADMIN' };
const UPDATE_FORM: UserUpdateForm = { username: 'bobby', role: 'USER' };

describe('UserService', () => {
  let api: jasmine.SpyObj<UserControllerService>;
  let authService: FakeAuthService;
  let service: UserService;

  beforeEach(() => {
    api = jasmine.createSpyObj<UserControllerService>('UserControllerService', [
      'listUsers',
      'createUser',
      'updateUser',
      'deleteUser',
      'resetPassword',
    ]);
    authService = fakeAuthService(TOKEN);
    TestBed.configureTestingModule({
      providers: [
        { provide: UserControllerService, useValue: api },
        { provide: AuthService, useValue: authService },
      ],
    });
    service = TestBed.inject(UserService);
  });

  const cases: CallCase<UserService>[] = [
    {
      name: 'listUsers with a search, a page and a size',
      invoke: (s) => s.listUsers('ali', 1, 10),
      api: () => api.listUsers,
      args: [BEARER, 'ali', 1, 10],
      response: restResponse(PAGE),
      expected: PAGE,
    },
    {
      name: 'listUsers without filters',
      invoke: (s) => s.listUsers(),
      api: () => api.listUsers,
      args: [BEARER, undefined, undefined, undefined],
      response: restResponse(PAGE),
      expected: PAGE,
    },
    {
      name: 'createUser',
      invoke: (s) => s.createUser(CREATE_FORM),
      api: () => api.createUser,
      args: [BEARER, CREATE_FORM],
      response: restResponse(USER),
      expected: USER,
    },
    {
      name: 'updateUser',
      invoke: (s) => s.updateUser('user-1', UPDATE_FORM),
      api: () => api.updateUser,
      args: [BEARER, 'user-1', UPDATE_FORM],
      response: restResponse(USER),
      expected: USER,
    },
    {
      name: 'deleteUser',
      invoke: (s) => s.deleteUser('user-1'),
      api: () => api.deleteUser,
      args: [BEARER, 'user-1'],
      response: restResponse(undefined),
      expected: undefined,
    },
    {
      name: 'resetPassword',
      invoke: (s) => s.resetPassword('user-1'),
      api: () => api.resetPassword,
      args: [BEARER, 'user-1'],
      response: restResponse('generated-password'),
      expected: 'generated-password',
    },
  ];
  describeCalls(() => service, cases);

  describeAuthorizationHeader({
    authService: () => authService,
    api: () => api.listUsers,
    invoke: () => service.listUsers(),
  });
});
