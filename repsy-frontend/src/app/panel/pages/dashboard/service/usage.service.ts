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

import { Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';

import { TotalUsageInfo } from '../../../../../generated/api';
import { UsageControllerService } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';

@Injectable({
  providedIn: 'root',
})
export class UsageService {
  constructor(
    private readonly usageControllerService: UsageControllerService,
    private readonly authService: AuthService,
  ) {}

  private get authorizationHeader(): string {
    return `Bearer ${this.authService.accessToken}`;
  }

  public getTotalUsage(): Observable<TotalUsageInfo> {
    return this.usageControllerService.getTotalUsage(this.authorizationHeader).pipe(map((r) => r.data!));
  }
}
