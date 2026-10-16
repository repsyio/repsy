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
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AccessTokensApi, AccessTokenScope } from '../../../../generated/api';
import { AuthService } from '../../../auth/pages/services/auth.service';
import { CliAuthComponent } from './cli-auth.component';
import { CliTokenDeliveryService } from './cli-token-delivery.service';

const auth = { username: 'alice' } as AuthService;
const delivery = jasmine.createSpyObj<CliTokenDeliveryService>('CliTokenDeliveryService', ['postToken']);
const post = delivery.postToken;
const flush = (): Promise<unknown> => new Promise((resolve) => setTimeout(resolve));
const created = {
  id: 'new',
  name: 'Repsy CLI login',
  scopes: [],
  token: 'rct-fake',
  expirationDate: 'x',
  createdAt: 'x',
};
post.and.resolveTo(true);

function apiWith(): jasmine.SpyObj<AccessTokensApi> {
  const api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', [
    'createCliToken',
    'revokePreviousCliTokens',
    'listAccessTokens',
    'createAccessToken',
    'revokeAccessToken',
  ]);
  api.createCliToken.and.returnValue(of(created) as never);
  api.revokePreviousCliTokens.and.returnValue(of(undefined) as never);
  return api;
}

function pageWith(query: Record<string, string>, api: jasmine.SpyObj<AccessTokensApi>): CliAuthComponent {
  const route = { snapshot: { queryParamMap: convertToParamMap(query) } } as unknown as ActivatedRoute;
  const component = new CliAuthComponent(route, api, auth, delivery);
  component.ngOnInit();
  return component;
}

function pageFor(query: Record<string, string>): CliAuthComponent {
  return pageWith(query, apiWith());
}

describe('CliAuthComponent', () => {
  beforeEach(() => {
    post.calls.reset();
    post.and.resolveTo(true);
  });

  it('shows the known scopes of the link', () => {
    const page = pageFor({ scopes: 'repo:read,repo:write', state: 'abc' });

    expect(page.scopes).toEqual(['repo:read', 'repo:write']);
    expect(page.scopeDetails.map((s) => s.scope)).toEqual(['repo:read', 'repo:write']);
  });

  it('drops scopes it does not know, scan:read and profile:read included', () => {
    expect(pageFor({ scopes: 'repo:read,profile:read,scan:read,admin' }).scopes).toEqual(['repo:read']);
  });

  it('selects the three repository scopes when the link names none the page knows', () => {
    const all: AccessTokenScope[] = ['repo:read', 'repo:write', 'repo:manage'];

    expect(pageFor({}).scopes).toEqual(all);
    expect(pageFor({ scopes: 'scan:read,admin' }).scopes).toEqual(all);
    expect(pageFor({ scopes: '' }).scopes).toEqual(all);
  });

  it('asks for no token name and no expiry: the token lives a fixed 24 hours', () => {
    const page = pageFor({ name: '<img src=x onerror=alert(1)>' });

    expect(page.lifetimeHours).toBe(24);
    expect('name' in page).toBeFalse();
  });

  it('shows the signed in account and creates nothing until Authorize', () => {
    const api = apiWith();
    const page = pageWith({ scopes: 'repo:read' }, api);

    expect(page.mode).toBe('approve');
    expect(page.username).toBe('alice');
    expect(page.canAuthorize).toBeTrue();
    expect(api.createCliToken).not.toHaveBeenCalled();
    expect(post).not.toHaveBeenCalled();
  });

  describe('with a redirect_uri', () => {
    const redirect = 'http://127.0.0.1:8123/callback';
    const link = { redirect_uri: redirect, state: 'a1b2', scopes: 'repo:read,repo:write' };

    it('shows the authorize screen and creates nothing until Authorize', () => {
      const api = apiWith();
      const page = pageWith(link, api);

      expect(page.mode).toBe('approve');
      expect(page.redirectUri).toBe(redirect);
      expect(api.createCliToken).not.toHaveBeenCalled();
      expect(post).not.toHaveBeenCalled();
    });

    it('on Authorize creates exactly one temporary token and posts the secret with the state unchanged', () => {
      const api = apiWith();
      const page = pageWith(link, api);

      page.authorize();

      expect(api.createCliToken).toHaveBeenCalledTimes(1);
      expect(api.createCliToken.calls.mostRecent().args[0].scopes as unknown).toEqual(['repo:read', 'repo:write']);
      expect(api.createAccessToken).not.toHaveBeenCalled();
      expect(post).toHaveBeenCalledOnceWith(redirect, 'rct-fake', 'a1b2');
      expect(page.mode).toBe('delivered');
      expect(page.tokenShown).toBeFalse();
    });

    it('posts no state when the link had none', () => {
      const page = pageWith({ redirect_uri: redirect }, apiWith());

      page.authorize();

      expect(post).toHaveBeenCalledOnceWith(redirect, 'rct-fake', null);
    });

    it('creates no token and posts nothing when the person declines', () => {
      const api = apiWith();
      const page = pageWith(link, api);

      page.decline();

      expect(page.mode).toBe('declined');
      expect(api.createCliToken).not.toHaveBeenCalled();
      expect(post).not.toHaveBeenCalled();
      page.authorize();
      expect(api.createCliToken).not.toHaveBeenCalled();
    });

    it('keeps the secret off the screen until asked, and can send it again', () => {
      const page = pageWith(link, apiWith());
      page.authorize();

      page.resend();
      expect(post).toHaveBeenCalledTimes(2);
      page.showToken();
      expect(page.tokenShown).toBeTrue();
    });

    it('shows the secret at once when the browser refused to send the form', async () => {
      post.and.resolveTo(false);
      const page = pageWith(link, apiWith());

      page.authorize();
      await flush();

      expect(page.mode).toBe('delivered');
      expect(page.deliveryBlocked).toBeTrue();
      expect(page.tokenShown).toBeTrue();
    });

    it('stays on the authorize screen, sending nothing, when the token cannot be created', () => {
      const api = apiWith();
      api.createCliToken.and.returnValue(throwError(() => new Error('down')) as never);
      const page = pageWith(link, api);

      page.authorize();

      expect(page.mode).toBe('approve');
      expect(page.authorizeFailed).toBeTrue();
      expect(page.authorizing).toBeFalse();
      expect(post).not.toHaveBeenCalled();
      expect(api.revokePreviousCliTokens).not.toHaveBeenCalled();
    });

    describe('the previous CLI token', () => {
      it('is revoked only after the new token exists and the browser sent it on', async () => {
        const api = apiWith();
        api.createCliToken.and.callFake((() => {
          expect(api.revokePreviousCliTokens).not.toHaveBeenCalled();
          return of(created);
        }) as never);
        const page = pageWith(link, api);

        page.authorize();
        await flush();

        expect(api.revokePreviousCliTokens).toHaveBeenCalledOnceWith('new');
        expect(page.revokeResult).toBe('revoked');
      });

      it('is not revoked when the browser refused to send the new token on', async () => {
        post.and.resolveTo(false);
        const api = apiWith();
        const page = pageWith(link, api);

        page.authorize();
        await flush();

        expect(page.deliveryBlocked).toBeTrue();
        expect(api.revokePreviousCliTokens).not.toHaveBeenCalled();
        expect(page.revokeResult).toBeNull();
      });

      it('is not revoked when creating the new token fails', async () => {
        const api = apiWith();
        api.createCliToken.and.returnValue(throwError(() => new Error('down')) as never);
        const page = pageWith(link, api);

        page.authorize();
        await flush();

        expect(api.revokePreviousCliTokens).not.toHaveBeenCalled();
      });

      it('still delivers the new token when revoking fails, and says so', async () => {
        const api = apiWith();
        api.revokePreviousCliTokens.and.returnValue(throwError(() => new Error('gone')) as never);
        const page = pageWith(link, api);

        page.authorize();
        await flush();

        expect(post).toHaveBeenCalledTimes(1);
        expect(page.mode).toBe('delivered');
        expect(page.revokeResult).toBe('failed');
      });
    });
  });

  describe('with a redirect_uri that is not a loopback address', () => {
    [
      'http://evil.com:8123/callback',
      'http://127.0.0.1.evil.com:8123/callback',
      'http://127.0.0.1@evil.com:8123/callback',
      'https://127.0.0.1:8123/callback',
      'http://127.0.0.1/callback',
      'http://localhost:8123/callback',
      '',
    ].forEach((redirect) => {
      it(`refuses ${JSON.stringify(redirect)}, creates no token and sends nothing`, async () => {
        const api = apiWith();
        const page = pageWith({ redirect_uri: redirect, state: 's' }, api);

        expect(page.mode).toBe('refused');
        expect(page.redirectUri).toBeNull();
        expect(page.canAuthorize).toBeFalse();
        page.authorize();
        await flush();
        expect(api.createCliToken).not.toHaveBeenCalled();
        expect(post).not.toHaveBeenCalled();
      });
    });

    it('offers the paste path on request, with no address to post to and no state', async () => {
      const api = apiWith();
      const page = pageWith({ redirect_uri: 'http://evil.com:1/x', state: 's' }, api);

      page.usePasteInstead();

      expect(page.mode).toBe('approve');
      expect(page.redirectUri).toBeNull();
      page.authorize();
      await flush();
      expect(api.createCliToken).toHaveBeenCalledTimes(1);
      expect(post).not.toHaveBeenCalled();
      expect(page.mode).toBe('pasted');
      expect(page.created?.token).toBe('rct-fake');
    });
  });

  describe('without a redirect_uri (paste flow)', () => {
    it('shows the authorize screen, and after Authorize shows the secret and posts nothing', async () => {
      const api = apiWith();
      const page = pageWith({ state: 'abc' }, api);

      expect(page.mode).toBe('approve');
      expect(page.redirectUri).toBeNull();
      expect(api.createCliToken).not.toHaveBeenCalled();

      page.authorize();
      await flush();

      expect(page.mode).toBe('pasted');
      expect(page.tokenShown).toBeTrue();
      expect(page.created?.token).toBe('rct-fake');
      expect(post).not.toHaveBeenCalled();
    });

    it('revokes the previous CLI token right after the secret is on screen', async () => {
      const api = apiWith();
      const page = pageWith({}, api);

      page.authorize();
      await flush();

      expect(api.revokePreviousCliTokens).toHaveBeenCalledOnceWith('new');
      expect(page.revokeResult).toBe('revoked');
    });

    it('creates no token when declined', () => {
      const api = apiWith();
      const page = pageWith({}, api);

      page.decline();

      expect(page.mode).toBe('declined');
      expect(api.createCliToken).not.toHaveBeenCalled();
    });
  });
});
