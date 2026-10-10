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
import { UsersApi } from '../../../../../generated/api';

@Injectable({
  providedIn: 'root',
})
export class UserService {
  constructor(private readonly usersApi: UsersApi) {}

  listUsers(q?: string, page?: number, size?: number): Observable<PagedModelUserResponse> {
    return this.usersApi.listUsers(q, page, size);
  }

  /** The number of admins on the server, whatever page or search the list shows. */
  countAdmins(): Observable<number> {
    return this.usersApi.countAdmins();
  }

  createUser(form: UserCreateForm): Observable<UserResponse> {
    return this.usersApi.createUser(form);
  }

  updateUser(userId: string, form: UserUpdateForm): Observable<UserResponse> {
    return this.usersApi.updateUser(userId, form);
  }

  deleteUser(userId: string): Observable<void> {
    return this.usersApi.deleteUser(userId).pipe(map(() => undefined));
  }

  resetPassword(userId: string): Observable<string> {
    return this.usersApi.resetPassword(userId);
  }
}
