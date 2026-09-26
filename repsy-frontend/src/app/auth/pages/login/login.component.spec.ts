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

import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, ChangeDetectorRef, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';

import { ToastService } from '../../../panel/shared/components/toast/toast.service';
import { AuthService } from '../service/auth.service';
import { LoginComponent } from './login.component';

const INVALID_CREDENTIALS_TEXT = 'Username or password is incorrect.';

describe('LoginComponent', () => {
  let component: LoginComponent;
  let authService: jasmine.SpyObj<AuthService>;
  let router: Router;
  let navigateByUrl: jasmine.Spy;
  let toastService: jasmine.SpyObj<ToastService>;
  let changeDetector: jasmine.SpyObj<ChangeDetectorRef>;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['logIn']);
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    router = TestBed.inject(Router);
    navigateByUrl = spyOn(router, 'navigateByUrl').and.resolveTo(true);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);

    changeDetector = jasmine.createSpyObj<ChangeDetectorRef>('ChangeDetectorRef', ['markForCheck']);

    component = new LoginComponent(router, new FormBuilder(), authService, toastService, changeDetector);
    component.ngOnInit();
    component.form.setValue({ username: 'someone', password: 'Passw0rd' });
  });

  function failLogin(status: number, error: unknown): void {
    authService.logIn.and.returnValue(throwError(() => new HttpErrorResponse({ status, error })));
    component.login();
  }

  it('navigates home after a successful login', () => {
    authService.logIn.and.returnValue(of(undefined));

    component.login();

    expect(navigateByUrl).toHaveBeenCalledOnceWith('/');
    expect(toastService.show).not.toHaveBeenCalled();
  });

  describe('form validity (RPS-1308)', () => {
    it('lets a password the creation rule would reject be submitted', () => {
      for (const password of ['a', 'abc', 'lowercase1', 'has space', 'x'.repeat(72)]) {
        component.form.setValue({ username: 'someone', password });

        expect(component.form.valid).withContext(password).toBeTrue();
      }
    });

    it('needs a password, at most 72 characters of it', () => {
      component.form.setValue({ username: 'someone', password: '' });
      expect(component.form.get('password')?.errors).toEqual({ required: true });

      component.form.setValue({ username: 'someone', password: 'x'.repeat(73) });
      expect(Object.keys(component.form.get('password')?.errors ?? {})).toEqual(['maxlength']);
    });

    it('still checks the username', () => {
      component.form.setValue({ username: 'ab', password: 'abc' });

      expect(component.form.valid).toBeFalse();
    });
  });

  describe('returnUrl (RPS-1278)', () => {
    function loginAt(url: string): void {
      spyOnProperty(router, 'url', 'get').and.returnValue(url);
      authService.logIn.and.returnValue(of(undefined));
      component.login();
    }

    it('returns to the page AuthGuard remembered', () => {
      loginAt('/?returnUrl=%2Fmy-repo%2Fpackages%3Ftab%3D1');

      expect(navigateByUrl).toHaveBeenCalledOnceWith('/my-repo/packages?tab=1');
    });

    it('also works on /login', () => {
      loginAt('/login?returnUrl=%2Frepositories');

      expect(navigateByUrl).toHaveBeenCalledOnceWith('/repositories');
    });

    ['https://evil.example/', '//evil.example', '/\\evil.example', 'javascript:alert(1)', ''].forEach((returnUrl) => {
      it(`ignores the unsafe returnUrl ${JSON.stringify(returnUrl)} and goes home`, () => {
        loginAt(`/?returnUrl=${encodeURIComponent(returnUrl)}`);

        expect(navigateByUrl).toHaveBeenCalledOnceWith('/');
      });
    });
  });

  it('shows the invalidCredentials text on a 401, which errorHandlerInterceptor leaves to the caller', () => {
    failLogin(401, { msgId: 'invalidCredentials', text: INVALID_CREDENTIALS_TEXT });

    expect(toastService.show).toHaveBeenCalledOnceWith(INVALID_CREDENTIALS_TEXT, 'error');
    expect(navigateByUrl).not.toHaveBeenCalled();
    expect(component.loading).toBeFalse();
    expect(component.form.enabled).toBeTrue();
    expect(changeDetector.markForCheck).toHaveBeenCalled();
  });

  it('falls back to a default message on a 401 without a body', () => {
    failLogin(401, null);

    expect(toastService.show).toHaveBeenCalledOnceWith(INVALID_CREDENTIALS_TEXT, 'error');
  });

  it('leaves other errors to errorHandlerInterceptor, which already shows them', () => {
    failLogin(500, { text: 'Server error' });

    expect(toastService.show).not.toHaveBeenCalled();
    expect(component.loading).toBeFalse();
    expect(component.form.enabled).toBeTrue();
  });
});

/**
 * RPS-1459: LoginComponent is OnPush, and at "/" it is created in the view container of the OnPush
 * AuthRedirectComponent. The answer of the login request is not an event of its template, so the view is only
 * refreshed if something marks it (the request's `finalize` does, RPS-1462; before that only `FormGroup.enable()`
 * did, as a side effect). A root OnPush component is always refreshed by `fixture.detectChanges()`, so it is
 * rendered inside a host, whose own OnPush view is only refreshed when marked.
 */
@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LoginComponent],
  template: '<app-login />',
})
class OnPushHostComponent {}

describe('LoginComponent in an OnPush host (RPS-1459)', () => {
  let fixture: ComponentFixture<OnPushHostComponent>;
  let answer: Subject<unknown>;

  const query = <T extends HTMLElement>(testId: string): T =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as T;
  const type = (testId: string, value: string): void => {
    const input = query<HTMLInputElement>(testId);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };

  beforeEach(() => {
    answer = new Subject();
    TestBed.configureTestingModule({
      imports: [OnPushHostComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { logIn: () => answer.asObservable() } },
        { provide: ToastService, useValue: { show: jasmine.createSpy('show') } },
      ],
    });
    fixture = TestBed.createComponent(OnPushHostComponent);
    fixture.detectChanges();
    type('login-username', 'someone');
    type('login-password', 'Passw0rd');
    fixture.detectChanges();
  });

  it('enables the submit button again when the credentials are refused, without another event', () => {
    expect(query<HTMLButtonElement>('login-submit').disabled).toBeFalse();
    query<HTMLButtonElement>('login-submit').click();
    fixture.detectChanges();
    expect(query<HTMLButtonElement>('login-submit').disabled).toBeTrue();

    // The 401 is the last event of the page.
    answer.error(new HttpErrorResponse({ status: 401, error: null }));
    fixture.detectChanges();

    expect(query<HTMLButtonElement>('login-submit').disabled).toBeFalse();
  });

  it('enables the submit button again although form.enable() marks nothing (RPS-1462)', () => {
    // The button only depends on `loading`, so the view has to be marked by the component itself, not as a side
    // effect of FormGroup.enable(): make that call a no-op.
    const login = fixture.debugElement.query(By.directive(LoginComponent)).componentInstance as LoginComponent;
    spyOn(login.form, 'enable');

    query<HTMLButtonElement>('login-submit').click();
    fixture.detectChanges();
    expect(query<HTMLButtonElement>('login-submit').disabled).toBeTrue();

    answer.error(new HttpErrorResponse({ status: 401, error: null }));
    fixture.detectChanges();

    expect(login.form.enable).toHaveBeenCalled();
    expect(query<HTMLButtonElement>('login-submit').disabled).toBeFalse();
  });
});
