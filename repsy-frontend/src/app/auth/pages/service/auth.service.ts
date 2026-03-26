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
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Inject, Injectable, PLATFORM_ID } from '@angular/core';
import { jwtDecode } from 'jwt-decode';
import { Observable, Subscriber, throwError } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { LoginInfo } from '../../../panel/shared/dto/login-info';
import { RestResponse } from '../../../panel/shared/dto/rest-response';
import { ErrorHandlerService } from '../../../shared/error-handler/error-handler.service';
import { LoginForm } from '../login/form/login-form';

interface TokenPayload {
  username?: string;
}

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private _accessToken: string;
  private _refreshToken: string;
  private _username: string;
  private _email: string;
  private readonly isBrowser: boolean;

  constructor(
    private readonly http: HttpClient,
    private readonly errorHandlerService: ErrorHandlerService,
    @Inject(PLATFORM_ID) platformId: object,
  ) {
    this.isBrowser = isPlatformBrowser(platformId);
    if (this.isBrowser) {
      this._email = localStorage.getItem('email');
      this._username = localStorage.getItem('username');
      this._accessToken = localStorage.getItem('token');
      this._refreshToken = localStorage.getItem('refresh-token');
    }
  }

  public get email(): string {
    return this._email;
  }

  public get username(): string {
    return this._username;
  }

  public get accessToken(): string {
    return this._accessToken;
  }

  public isAuthorized(username: string): boolean {
    if (!this.isAuthenticated()) {
      return false;
    }

    const decoded = jwtDecode<TokenPayload>(this._accessToken);

    return decoded.username === username;
  }

  public isAuthenticated(): boolean {
    return !!(this._accessToken && this._refreshToken);
  }

  public refreshToken(): Observable<string> {
    if (!this._refreshToken) {
      return throwError(new Error('No refresh token presents.'));
    }

    return new Observable<string>((observer: Subscriber<string>) => {
      const url = environment.apiBaseUrl + '/api/auth/refresh-token';

      this.http.post<RestResponse<LoginInfo>>(url, { refreshToken: this._refreshToken }).subscribe(
        (res: RestResponse<LoginInfo>) => {
          this._update(res.data.email, res.data.username, res.data.token, res.data.refreshToken);
          observer.next(res.data.token);
          observer.complete();
        },
        (error: HttpErrorResponse) => {
          observer.error(error);
          observer.complete();
        },
      );
    });
  }

  public logIn(form: LoginForm): Promise<void> {
    return new Promise((resolve, reject) => {
      const url = environment.apiBaseUrl + '/api/auth/login';
      this.http
        .post<RestResponse<LoginInfo>>(url, form)
        .toPromise()
        .then((res: RestResponse<LoginInfo>) => {
          this._update(res.data.email, res.data.username, res.data.token, res.data.refreshToken);
          resolve();
        })
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }

  // public preLogIn(form: LoginForm): Promise<PreLoginInfo> {
  //   return new Promise((resolve, reject) => {
  //     const url = environment.apiBaseUrl + '/api/auth/pre-login';
  //     this.http
  //       .post<RestResponse<PreLoginInfo>>(url, form)
  //       .toPromise()
  //       .then((res: RestResponse<PreLoginInfo>) => resolve(res.data))
  //       .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
  //   });
  // }
  //
  // public async register(form: RegistrationForm): Promise<LoginInfo> {
  //   return new Promise<LoginInfo>((resolve, reject) => {
  //     return this.http
  //       .post<RestResponse<LoginInfo>>(environment.apiBaseUrl + '/api/auth/register', form)
  //       .toPromise()
  //       .then((res: RestResponse<LoginInfo>) => {
  //         this._update(res.data.email, res.data.username, res.data.token, res.data.refreshToken);
  //         resolve(res.data);
  //       })
  //       .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
  //   });
  // }

  public logOut(): void {
    this._email = null;
    this._username = null;
    this._accessToken = null;
    this._refreshToken = null;

    if (this.isBrowser) {
      localStorage.removeItem('email');
      localStorage.removeItem('username');
      localStorage.removeItem('token');
      localStorage.removeItem('refresh-token');
    }
  }

  private _update(email: string, username: string, accessToken: string, refreshToken: string): void {
    this._email = email;
    this._username = username;
    this._accessToken = accessToken;
    this._refreshToken = refreshToken;

    if (this.isBrowser) {
      localStorage.setItem('email', this._email);
      localStorage.setItem('username', this._username);
      localStorage.setItem('token', this._accessToken);
      localStorage.setItem('refresh-token', this._refreshToken);
    }
  }

  public updateLoginInfo(info: LoginInfo) {
    this._update(info.email, info.username, info.token, info.refreshToken);
  }
}
