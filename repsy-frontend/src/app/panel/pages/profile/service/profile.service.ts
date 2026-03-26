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

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';

import { environment } from '../../../../../environments/environment';
import { ErrorHandlerService } from '../../../../shared/error-handler/error-handler.service';
import { LoginInfo } from '../../../shared/dto/login-info';
import { RestResponse } from '../../../shared/dto/rest-response';
import { PasswordForm } from '../dto/password-form';
import { ProfileInfo } from '../dto/profile-info';

@Injectable({
  providedIn: 'root',
})
export class ProfileService {
  constructor(
    private readonly http: HttpClient,
    private readonly errorHandlerService: ErrorHandlerService,
  ) {}

  public async get(): Promise<ProfileInfo> {
    return new Promise<ProfileInfo>((resolve, reject) => {
      this.http
        .get<RestResponse<ProfileInfo>>(`${environment.apiBaseUrl}/api/profile`)
        .toPromise()
        .then((res: RestResponse<ProfileInfo>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }

  public async updatePassword(password: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const form = new PasswordForm();
      form.password = password;

      this.http
        .put<RestResponse<null>>(`${environment.apiBaseUrl}/api/profile/password`, form)
        .toPromise()
        .then((res: RestResponse<void>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }

  public async updateUsername(username: string): Promise<LoginInfo> {
    return new Promise<LoginInfo>((resolve, reject) => {
      this.http
        .put<RestResponse<LoginInfo>>(`${environment.apiBaseUrl}/api/profile/username`, { username })
        .toPromise()
        .then((res: RestResponse<LoginInfo>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }

  public async deleteAccount(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.http
        .delete<RestResponse<void>>(`${environment.apiBaseUrl}/api/profile`)
        .toPromise()
        .then((res: RestResponse<void>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }
}
