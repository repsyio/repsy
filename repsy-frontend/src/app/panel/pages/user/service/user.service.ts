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

import { PagedModelUserResponse, UserCreateForm, UserResponse, UserUpdateForm } from '../../../../../generated/api';
import { UserControllerService } from '../../../../../generated/api';

@Injectable({
  providedIn: 'root',
})
export class UserService {
  constructor(private readonly userControllerService: UserControllerService) {}

  public listUsers(search?: string, page?: number, size?: number): Observable<PagedModelUserResponse> {
    return this.userControllerService.listUsers(search, page, size).pipe(map((r) => r.data!));
  }

  /** The number of admins on the server, whatever page or search the list shows. */
  public countAdmins(): Observable<number> {
    return this.userControllerService.countAdmins().pipe(map((r) => r.data!));
  }

  public createUser(form: UserCreateForm): Observable<UserResponse> {
    return this.userControllerService.createUser(form).pipe(map((r) => r.data!));
  }

  public updateUser(userId: string, form: UserUpdateForm): Observable<UserResponse> {
    return this.userControllerService.updateUser(userId, form).pipe(map((r) => r.data!));
  }

  public deleteUser(userId: string): Observable<void> {
    return this.userControllerService.deleteUser(userId).pipe(map(() => undefined));
  }

  public resetPassword(userId: string): Observable<string> {
    return this.userControllerService.resetPassword(userId).pipe(map((r) => r.data!));
  }
}
