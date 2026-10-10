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
import { Observable, tap } from 'rxjs';

import { LoginInfo, ProfileInfo } from '../../../../../generated/api';
import { ProfileApi } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/services/auth.service';

@Injectable({
  providedIn: 'root',
})
export class ProfileService {
  constructor(
    private readonly profileApi: ProfileApi,
    private readonly authService: AuthService,
  ) {}

  get(): Observable<ProfileInfo> {
    return this.profileApi.getProfile();
  }

  updatePassword(password: string): Observable<LoginInfo> {
    return this.profileApi
      .updatePassword({ password })
      .pipe(tap((loginInfo) => this.authService.updateLoginInfo(loginInfo)));
  }

  updateUsername(username: string): Observable<LoginInfo> {
    return this.profileApi.updateUsername({ username });
  }

  deleteAccount(): Observable<void> {
    return this.profileApi.deleteProfile();
  }
}
