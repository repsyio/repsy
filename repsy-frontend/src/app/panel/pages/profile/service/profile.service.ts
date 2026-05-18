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

import { LoginInfo, ProfileInfo } from '../../../../../generated/api';
import { ProfileControllerService } from '../../../../../generated/api';
import { AuthService } from '../../../../auth/pages/service/auth.service';

@Injectable({
  providedIn: 'root',
})
export class ProfileService {
  constructor(
    private readonly profileControllerService: ProfileControllerService,
    private readonly authService: AuthService,
  ) {}

  private get authorizationHeader(): string {
    return `Bearer ${this.authService.accessToken}`;
  }

  public get(): Observable<ProfileInfo> {
    return this.profileControllerService.getProfile(this.authorizationHeader).pipe(map((r) => r.data!));
  }

  public updatePassword(password: string): Observable<void> {
    return this.profileControllerService
      .updatePassword(this.authorizationHeader, { password })
      .pipe(map(() => undefined));
  }

  public updateUsername(username: string): Observable<LoginInfo> {
    return this.profileControllerService
      .updateUsername(this.authorizationHeader, { username })
      .pipe(map((r) => r.data!));
  }

  public deleteAccount(): Observable<void> {
    return this.profileControllerService.deleteProfile(this.authorizationHeader).pipe(map(() => undefined));
  }
}
