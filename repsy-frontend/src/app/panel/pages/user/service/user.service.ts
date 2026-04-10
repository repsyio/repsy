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

import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';

import { environment } from '../../../../../environments/environment';
import { ErrorHandlerService } from '../../../../shared/error-handler/error-handler.service';
import { PagedData } from '../../../shared/dto/paged-data';
import { RestResponse } from '../../../shared/dto/rest-response';
import { UserInfo } from '../dto/user.info';
import { UserCreateForm } from '../form/user-crete-form';
import { UserUpdateForm } from '../form/user-update-form';

@Injectable({
  providedIn: 'root',
})
export class UserService {
  private readonly baseUrl = `${environment.apiBaseUrl}/api/users`;

  constructor(
    private readonly http: HttpClient,
    private readonly errorHandlerService: ErrorHandlerService,
  ) {}

  public async getUsers(pageNum: number, pageSize: number, searchQuery?: string): Promise<PagedData<UserInfo>> {
    return new Promise<PagedData<UserInfo>>((resolve, reject) => {
      let params = new HttpParams().set('page', pageNum.toString()).set('size', pageSize.toString());

      if (searchQuery && searchQuery.trim()) {
        params = params.set('search', searchQuery.trim());
      }

      this.http
        .get<RestResponse<PagedData<UserInfo>>>(this.baseUrl, { params })
        .toPromise()
        .then((res: RestResponse<PagedData<UserInfo>>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }

  public async createUser(form: UserCreateForm): Promise<UserInfo> {
    return new Promise<UserInfo>((resolve, reject) => {
      this.http
        .post<RestResponse<UserInfo>>(this.baseUrl, form)
        .toPromise()
        .then((res: RestResponse<UserInfo>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }

  public async updateUser(userId: string, form: UserUpdateForm): Promise<UserInfo> {
    return new Promise<UserInfo>((resolve, reject) => {
      this.http
        .put<RestResponse<UserInfo>>(`${this.baseUrl}/${userId}`, form)
        .toPromise()
        .then((res: RestResponse<UserInfo>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }

  public async deleteUser(userId: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.http
        .delete<RestResponse<void>>(`${this.baseUrl}/${userId}`)
        .toPromise()
        .then(() => {
          resolve();
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }

  public async resetPassword(userId: string): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      this.http
        .post<RestResponse<string>>(`${this.baseUrl}/${userId}/actions/reset-password`, {})
        .toPromise()
        .then((res: RestResponse<string>) => {
          resolve(res.data);
        })
        .catch((res: HttpErrorResponse) => {
          reject(this.errorHandlerService.handle(res));
        });
    });
  }
}
