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

import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Inject, Injectable, PLATFORM_ID } from '@angular/core';
import { firstValueFrom, map, Observable, throwError } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { AuthControllerService } from '../../../../generated/api';

interface AdminLoginResponse {
  data: { username: string; token: string; refreshToken: string };
}

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private _accessToken: string;
  private _refreshToken: string;
  private _username: string;
  private readonly isBrowser: boolean;

  constructor(
    private readonly authControllerService: AuthControllerService,
    private readonly http: HttpClient,
    @Inject(PLATFORM_ID) platformId: object,
  ) {
    this.isBrowser = isPlatformBrowser(platformId);
    if (this.isBrowser) {
      this._username = localStorage.getItem('username');
      this._accessToken = localStorage.getItem('token');
      this._refreshToken = localStorage.getItem('refresh-token');
    }
  }

  public get username(): string {
    return this._username;
  }

  public get accessToken(): string {
    return this._accessToken;
  }

  public isAuthenticated(): boolean {
    return !!(this._accessToken && this._refreshToken);
  }

  public async logInAdmin(password: string): Promise<void> {
    const response = await firstValueFrom(
      this.http.post<AdminLoginResponse>(`${environment.apiBaseUrl}/api/auth/admin/login`, { password }),
    );

    this._update(response.data.username, response.data.token, response.data.refreshToken);
  }

  public refreshToken(): Observable<string> {
    if (!this._refreshToken) {
      return throwError(new Error('No refresh token presents.'));
    }
    return this.authControllerService.refreshToken({ refreshToken: this._refreshToken }).pipe(
      map((r) => {
        this._update(r.data!.username!, r.data!.token!, r.data!.refreshToken!);
        return r.data!.token!;
      }),
    );
  }

  public applyExternalSession(username: string, token: string, refreshToken: string): void {
    this._update(username, token, refreshToken);
  }

  public logOut(): void {
    this._username = null;
    this._accessToken = null;
    this._refreshToken = null;

    if (this.isBrowser) {
      localStorage.removeItem('username');
      localStorage.removeItem('token');
      localStorage.removeItem('refresh-token');
    }
  }

  public logOutToGitNode(): void {
    this.logOut();

    if (this.isBrowser) {
      window.location.href = environment.gitnodeUrl;
    }
  }

  private _update(username: string, accessToken: string, refreshToken: string): void {
    this._username = username;
    this._accessToken = accessToken;
    this._refreshToken = refreshToken;

    if (this.isBrowser) {
      localStorage.setItem('username', this._username);
      localStorage.setItem('token', this._accessToken);
      localStorage.setItem('refresh-token', this._refreshToken);
    }
  }
}
