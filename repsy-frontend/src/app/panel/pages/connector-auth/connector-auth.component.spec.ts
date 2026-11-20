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
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AccessTokensApi } from '../../../../generated/api';
import { AuthService } from '../../../auth/pages/services/auth.service';
import { ConnectorAuthComponent } from './connector-auth.component';
import { ConnectorRedirectService } from './connector-redirect.service';

const REDIRECT = 'https://mcp.repsy.io/oauth/upstream/callback';
const CHALLENGE = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM';
const auth = { username: 'alice' } as AuthService;
const valid = {
  redirect_uri: REDIRECT,
  state: 'xyz',
  scopes: 'repo:read',
  client_name: 'Claude',
  code_challenge: CHALLENGE,
  code_challenge_method: 'S256',
};

function apiWith(): jasmine.SpyObj<AccessTokensApi> {
  const api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', ['createConnectorAuthorization']);
  api.createConnectorAuthorization.and.returnValue(of({ id: 'id', code: 'c0de', expiresAt: 'x' }) as never);
  return api;
}

function pageWith(
  query: Record<string, string>,
  api: jasmine.SpyObj<AccessTokensApi>,
  redirect: jasmine.SpyObj<ConnectorRedirectService>,
): ConnectorAuthComponent {
  const route = { snapshot: { queryParamMap: convertToParamMap(query) } } as unknown as ActivatedRoute;
  const component = new ConnectorAuthComponent(route, api, auth, redirect);
  component.ngOnInit();
  return component;
}

describe('ConnectorAuthComponent', () => {
  let api: jasmine.SpyObj<AccessTokensApi>;
  let redirect: jasmine.SpyObj<ConnectorRedirectService>;

  beforeEach(() => {
    api = apiWith();
    redirect = jasmine.createSpyObj<ConnectorRedirectService>('ConnectorRedirectService', ['redirect']);
  });

  it('shows the application, the account and the known scopes of a valid link', () => {
    const page = pageWith({ ...valid, scopes: 'repo:read,scan:read,profile:read' }, api, redirect);

    expect(page.mode).toBe('approve');
    expect(page.clientName).toBe('Claude');
    expect(page.username).toBe('alice');
    expect(page.scopes).toEqual(['repo:read']);
    expect(page.scopeDetails.map((s) => s.scope)).toEqual(['repo:read']);
    expect(page.redirectUri).toBe(REDIRECT);
  });

  it('names the application after the host of its redirect when the link gives no name', () => {
    expect(pageWith({ ...valid, client_name: ' ' }, api, redirect).clientName).toBe('mcp.repsy.io');
  });

  it('refuses a link without a usable redirect, a S256 challenge or a scope, and asks nothing', () => {
    for (const broken of [
      { ...valid, redirect_uri: 'http://evil.test/cb' },
      { ...valid, code_challenge: '' },
      { ...valid, code_challenge_method: 'plain' },
      { ...valid, scopes: 'scan:read' },
    ]) {
      const page = pageWith(broken, api, redirect);
      page.authorize();
      expect(page.mode).toBe('invalid');
    }
    expect(api.createConnectorAuthorization).not.toHaveBeenCalled();
  });

  it('asks for a code on Authorize and sends the browser back with the code and the state', () => {
    const page = pageWith(valid, api, redirect);

    page.authorize();

    expect(api.createConnectorAuthorization).toHaveBeenCalledOnceWith({
      redirectUri: REDIRECT,
      clientName: 'Claude',
      scopes: ['repo:read'] as never,
      codeChallenge: CHALLENGE,
      codeChallengeMethod: 'S256',
    });
    expect(page.mode).toBe('redirecting');
    expect(redirect.redirect).toHaveBeenCalledOnceWith(`${REDIRECT}?code=c0de&state=xyz`);
  });

  it('declines without asking anything and without following the redirect', () => {
    const page = pageWith(valid, api, redirect);

    page.decline();
    page.authorize();

    expect(page.mode).toBe('declined');
    expect(api.createConnectorAuthorization).not.toHaveBeenCalled();
    expect(redirect.redirect).not.toHaveBeenCalled();
  });

  it('says so when the server does not offer connected applications', () => {
    api.createConnectorAuthorization.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    const page = pageWith(valid, api, redirect);

    page.authorize();

    expect(page.mode).toBe('unavailable');
    expect(redirect.redirect).not.toHaveBeenCalled();
  });

  it('says so when the server does not know the redirect', () => {
    api.createConnectorAuthorization.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { code: 'connectorRedirectUriNotRegistered' } })),
    );
    const page = pageWith(valid, api, redirect);

    page.authorize();

    expect(page.mode).toBe('unregistered');
  });

  it('lets the person try again after any other failure', () => {
    api.createConnectorAuthorization.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const page = pageWith(valid, api, redirect);

    page.authorize();

    expect(page.mode).toBe('approve');
    expect(page.authorizeFailed).toBeTrue();
    expect(page.canAuthorize).toBeTrue();
  });
});
