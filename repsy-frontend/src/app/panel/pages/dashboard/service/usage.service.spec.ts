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

import { TotalUsageInfo, UsageControllerService } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';
import {
  describeAuthorizationHeader,
  FakeAuthService,
  fakeAuthService,
} from '../../../shared/testing/authorization-header-spec-helpers';
import { CallCase, describeCalls, restResponse } from '../../repository/testing/protocol-service-spec-helpers';
import { UsageService } from './usage.service';

const TOKEN = 'access-token';
const TOTAL_USAGE: TotalUsageInfo = { diskUsed: { value: 2048, text: '2 KB' }, reposCount: 3 };

describe('UsageService', () => {
  let api: jasmine.SpyObj<UsageControllerService>;
  let authService: FakeAuthService;
  let service: UsageService;

  beforeEach(() => {
    api = jasmine.createSpyObj<UsageControllerService>('UsageControllerService', ['getTotalUsage']);
    authService = fakeAuthService(TOKEN);
    TestBed.configureTestingModule({
      providers: [
        { provide: UsageControllerService, useValue: api },
        { provide: AuthService, useValue: authService },
      ],
    });
    service = TestBed.inject(UsageService);
  });

  const cases: CallCase<UsageService>[] = [
    {
      name: 'getTotalUsage',
      invoke: (s) => s.getTotalUsage(),
      api: () => api.getTotalUsage,
      args: [`Bearer ${TOKEN}`],
      response: restResponse(TOTAL_USAGE),
      expected: TOTAL_USAGE,
    },
  ];
  describeCalls(() => service, cases);

  describeAuthorizationHeader({
    authService: () => authService,
    api: () => api.getTotalUsage,
    invoke: () => service.getTotalUsage(),
  });
});
