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

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { SplashService } from '../../../shared/components/splash-screen/splasht.service';
import { AuthService } from '../../pages/service/auth.service';
import { AuthRedirectComponent } from './auth-redirect.component';

// RPS-1278: "/" renders the login form in place, so a login changes no route; the component has to
// follow the session by itself.
describe('AuthRedirectComponent', () => {
  let session$: BehaviorSubject<boolean>;
  let fixture: ComponentFixture<AuthRedirectComponent>;
  let splash: SplashService;

  const has = (selector: string): boolean => !!fixture.nativeElement.querySelector(selector);
  const settle = async (): Promise<void> => {
    // The views are lazy chunks: wait for the import to resolve and the view to render.
    for (let i = 0; i < 50; i++) {
      await fixture.whenStable();
      await new Promise((resolve) => setTimeout(resolve, 20));
      fixture.detectChanges();
      if (!splash.loading) {
        return;
      }
    }
  };

  function create(authenticated: boolean): void {
    session$ = new BehaviorSubject<boolean>(authenticated);
    TestBed.configureTestingModule({
      imports: [AuthRedirectComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => session$.value,
            isAuthenticated$: session$.asObservable(),
            username: 'alice',
            logIn: () => undefined,
          },
        },
      ],
    });
    splash = TestBed.inject(SplashService);
    fixture = TestBed.createComponent(AuthRedirectComponent);
    fixture.detectChanges();
  }

  it('renders the login form while there is no session', async () => {
    create(false);
    await settle();

    expect(has('[data-testid="login-form"]')).toBeTrue();
    expect(has('app-dashboard')).toBeFalse();
    expect(splash.loading).toBeFalse();
  });

  it('renders the dashboard for an existing session', async () => {
    create(true);
    await settle();

    expect(has('app-dashboard')).toBeTrue();
    expect(has('[data-testid="login-form"]')).toBeFalse();
  });

  it('swaps the login form for the dashboard when the session starts, without a route change', async () => {
    create(false);
    await settle();
    expect(has('[data-testid="login-form"]')).toBeTrue();

    session$.next(true);
    await settle();

    expect(has('app-dashboard')).toBeTrue();
    expect(has('[data-testid="login-form"]')).toBeFalse();
  });

  it('swaps back to the login form when the session ends', async () => {
    create(true);
    await settle();

    session$.next(false);
    await settle();

    expect(has('[data-testid="login-form"]')).toBeTrue();
    expect(has('app-dashboard')).toBeFalse();
  });

  it('renders only the latest state when the session flips while a view is loading', async () => {
    create(false);
    session$.next(true);
    session$.next(false);
    await settle();

    expect(has('[data-testid="login-form"]')).toBeTrue();
    expect(has('app-dashboard')).toBeFalse();
    expect(fixture.nativeElement.querySelectorAll('app-login').length).toBe(1);
  });

  it('releases the splash screen when it is destroyed while a view is loading', () => {
    create(false);
    expect(splash.loading).toBeTrue();

    fixture.destroy();

    expect(splash.loading).toBeFalse();
  });
});
