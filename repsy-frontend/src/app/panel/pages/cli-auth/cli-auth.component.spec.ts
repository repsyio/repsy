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

function liveToken(i: number): unknown {
  return {
    id: `t${i}`,
    name: `t${i}`,
    scopes: [],
    expirationDate: '2999-01-01T00:00:00Z',
    createdAt: '2026-01-01T00:00:00Z',
  };
}

const auth = { username: 'alice' } as AuthService;
const delivery = jasmine.createSpyObj<CliTokenDeliveryService>('CliTokenDeliveryService', ['postToken']);
const post = delivery.postToken;
const flush = (): Promise<unknown> => new Promise((resolve) => setTimeout(resolve));
post.and.resolveTo(true);

function apiWith(tokens: unknown[] = []): jasmine.SpyObj<AccessTokensApi> {
  const api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', [
    'listAccessTokens',
    'createAccessToken',
    'revokeAccessToken',
  ]);
  api.listAccessTokens.and.returnValue(of({ content: tokens, page: {} }) as never);
  api.revokeAccessToken.and.returnValue(of(undefined) as never);
  return api;
}

function pageWith(query: Record<string, string>, api: jasmine.SpyObj<AccessTokensApi>): CliAuthComponent {
  const route = { snapshot: { queryParamMap: convertToParamMap(query) } } as unknown as ActivatedRoute;
  const component = new CliAuthComponent(route, api, auth, delivery);
  component.ngOnInit();
  return component;
}

function pageFor(query: Record<string, string>, tokens: unknown[] = []): CliAuthComponent {
  return pageWith(query, apiWith(tokens));
}

describe('CliAuthComponent', () => {
  it('pre-fills the name and the known scopes of the link', () => {
    const page = pageFor({ name: 'laptop', scopes: 'repo:read,repo:write', state: 'abc' });

    expect(page.name).toBe('laptop');
    expect(page.scopes).toEqual(['repo:read', 'repo:write']);
  });

  it('drops scopes it does not know, scan:read and profile:read included', () => {
    expect(pageFor({ scopes: 'repo:read,profile:read,scan:read,admin' }).scopes).toEqual(['repo:read']);
  });

  it('ticks the three repository scopes when the link names none the page knows', () => {
    const all: AccessTokenScope[] = ['repo:read', 'repo:write', 'repo:manage'];

    expect(pageFor({}).scopes).toEqual(all);
    expect(pageFor({ scopes: 'scan:read,admin' }).scopes).toEqual(all);
    expect(pageFor({ scopes: '' }).scopes).toEqual(all);
  });

  it('falls back to a default name', () => {
    expect(pageFor({}).name).toBe('Repsy CLI');
  });

  it('cuts a long name to what the backend accepts and keeps markup as plain text', () => {
    expect(pageFor({ name: 'x'.repeat(500) }).name.length).toBe(80);
    expect(pageFor({ name: '<img src=x onerror=alert(1)>' }).name).toBe('<img src=x onerror=alert(1)>');
  });

  it('blocks the create button when 50 tokens have not expired', () => {
    const full = Array.from({ length: 50 }, (_, i) => liveToken(i));

    expect(pageFor({}, full).blockedReason).toContain('50 access tokens');
    expect(pageFor({}, full.slice(1)).blockedReason).toBeNull();
  });

  it('does not count expired tokens against the limit', () => {
    const expired = { ...(liveToken(1) as object), expirationDate: '2000-01-01T00:00:00Z' };

    expect(
      pageFor(
        {},
        Array.from({ length: 60 }, () => expired),
      ).blockedReason,
    ).toBeNull();
  });

  it('reports the check of the token count: under way, done, or failed', () => {
    const route = { snapshot: { queryParamMap: convertToParamMap({}) } } as unknown as ActivatedRoute;
    const api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', ['listAccessTokens']);
    api.listAccessTokens.and.returnValue(throwError(() => new Error('down')) as never);
    const failed = new CliAuthComponent(route, api, auth, delivery);

    expect(failed.checkingRoom).toBeTrue();
    failed.ngOnInit();

    expect(failed.checkingRoom).toBeFalse();
    expect(failed.roomCheckFailed).toBeTrue();
    expect(failed.blockedReason).toBeNull();
    expect(pageFor({}).checkingRoom).toBeFalse();
    expect(pageFor({}).roomCheckFailed).toBeFalse();
  });

  it('shows the secret once the token is created', () => {
    const page = pageFor({});
    expect(page.created).toBeNull();

    page.onCreated({ token: 'rut-x' } as never);

    expect(page.created?.token).toBe('rut-x');
  });

  describe('with a redirect_uri', () => {
    const redirect = 'http://127.0.0.1:8123/callback';
    const link = { redirect_uri: redirect, state: 'a1b2', name: 'laptop', scopes: 'repo:read,repo:write' };
    const created = { id: 'new', name: 'laptop', scopes: [], token: 'rut-fake', expirationDate: 'x', createdAt: 'x' };

    beforeEach(() => {
      post.calls.reset();
      post.and.resolveTo(true);
    });

    function approvable(tokens: unknown[] = []): { page: CliAuthComponent; api: jasmine.SpyObj<AccessTokensApi> } {
      const api = apiWith(tokens);
      api.createAccessToken.and.returnValue(of(created) as never);
      return { page: pageWith(link, api), api };
    }

    it('shows an approval screen and creates nothing until Approve', () => {
      const { page, api } = approvable();

      expect(page.mode).toBe('approve');
      expect(page.username).toBe('alice');
      expect(page.scopeDetails.map((s) => s.scope)).toEqual(['repo:read', 'repo:write']);
      expect(page.canApprove).toBeTrue();
      expect(api.createAccessToken).not.toHaveBeenCalled();
      expect(post).not.toHaveBeenCalled();
    });

    it('on Approve creates exactly one token and posts the secret with the state unchanged', () => {
      const { page, api } = approvable();

      page.approve();

      expect(api.createAccessToken).toHaveBeenCalledTimes(1);
      const form = api.createAccessToken.calls.mostRecent().args[0];
      expect(form.name).toBe('laptop');
      expect(form.scopes as unknown).toEqual(['repo:read', 'repo:write']);
      expect(new Date(form.expirationDate!).getTime()).toBeGreaterThan(Date.now() + 364 * 24 * 3600 * 1000);
      expect(post).toHaveBeenCalledOnceWith(redirect, 'rut-fake', 'a1b2');
      expect(page.mode).toBe('delivered');
      expect(page.tokenShown).toBeFalse();
    });

    it('posts no state when the link had none', () => {
      const api = apiWith();
      api.createAccessToken.and.returnValue(of(created) as never);
      const page = pageWith({ redirect_uri: redirect }, api);

      page.approve();

      expect(post).toHaveBeenCalledOnceWith(redirect, 'rut-fake', null);
    });

    it('creates no token and posts nothing when the person declines', () => {
      const { page, api } = approvable();

      page.decline();

      expect(page.mode).toBe('declined');
      expect(api.createAccessToken).not.toHaveBeenCalled();
      expect(post).not.toHaveBeenCalled();
      page.approve();
      expect(api.createAccessToken).not.toHaveBeenCalled();
    });

    it('keeps the secret off the screen until asked, and can send it again', () => {
      const { page } = approvable();
      page.approve();

      page.resend();
      expect(post).toHaveBeenCalledTimes(2);
      page.showToken();
      expect(page.tokenShown).toBeTrue();
    });

    it('shows the secret at once when the browser refused to send the form', async () => {
      post.and.resolveTo(false);
      const { page } = approvable();

      page.approve();
      await Promise.resolve();
      await Promise.resolve();

      expect(page.mode).toBe('delivered');
      expect(page.deliveryBlocked).toBeTrue();
      expect(page.tokenShown).toBeTrue();
    });

    it('stays on the approval screen, sending nothing, when the token cannot be created', () => {
      const api = apiWith();
      api.createAccessToken.and.returnValue(throwError(() => new Error('down')) as never);
      const page = pageWith(link, api);

      page.approve();

      expect(page.mode).toBe('approve');
      expect(page.approveFailed).toBeTrue();
      expect(page.approving).toBeFalse();
      expect(post).not.toHaveBeenCalled();
      expect(api.revokeAccessToken).not.toHaveBeenCalled();
    });

    it('blocks Approve at the limit of 50 live tokens', () => {
      const full = Array.from({ length: 50 }, (_, i) => liveToken(i));
      const { page, api } = approvable(full);

      expect(page.blockedReason).toContain('50 access tokens');
      expect(page.canApprove).toBeFalse();
      page.approve();
      expect(api.createAccessToken).not.toHaveBeenCalled();
    });

    describe('older token with the same name', () => {
      const old = { ...(liveToken(1) as object), id: 'old', name: 'laptop' };
      const other = { ...(liveToken(2) as object), id: 'other', name: 'other' };
      const expiredSame = {
        ...(liveToken(3) as object),
        id: 'dead',
        name: 'laptop',
        expirationDate: '2000-01-01T00:00:00Z',
      };

      it('offers to revoke it, on by default, and revokes only that one after the new token exists', async () => {
        const { page, api } = approvable([old, other, expiredSame]);
        expect(page.sameNameTokens.map((t) => t.id)).toEqual(['old']);
        expect(page.revokeOld).toBeTrue();

        api.revokeAccessToken.calls.reset();
        api.createAccessToken.and.callFake((() => {
          expect(api.revokeAccessToken).not.toHaveBeenCalled();
          return of(created);
        }) as never);
        page.approve();
        await flush();

        expect(api.revokeAccessToken).toHaveBeenCalledOnceWith('old');
        expect(page.revokeResult).toBe('revoked');
      });

      it('does not revoke it when the browser refused to send the new token on', async () => {
        post.and.resolveTo(false);
        const { page, api } = approvable([old]);

        page.approve();
        await flush();

        expect(page.deliveryBlocked).toBeTrue();
        expect(api.revokeAccessToken).not.toHaveBeenCalled();
      });

      it('keeps it when the box is unticked', async () => {
        const { page, api } = approvable([old]);
        page.revokeOld = false;

        page.approve();
        await flush();

        expect(api.revokeAccessToken).not.toHaveBeenCalled();
      });

      it('does not revoke it when creating the new token fails', async () => {
        const api = apiWith([old]);
        api.createAccessToken.and.returnValue(throwError(() => new Error('down')) as never);
        const page = pageWith(link, api);

        page.approve();
        await flush();

        expect(api.revokeAccessToken).not.toHaveBeenCalled();
      });

      it('still delivers the new token when revoking the old one fails, and says so', async () => {
        const { page, api } = approvable([old]);
        api.revokeAccessToken.and.returnValue(throwError(() => new Error('gone')) as never);

        page.approve();
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
        post.calls.reset();
        const api = apiWith();
        const page = pageWith({ redirect_uri: redirect, state: 's', name: 'x' }, api);

        expect(page.mode).toBe('refused');
        expect(page.redirectUri).toBeNull();
        expect(page.canApprove).toBeFalse();
        page.approve();
        await flush();
        expect(api.createAccessToken).not.toHaveBeenCalled();
        expect(post).not.toHaveBeenCalled();
      });
    });

    it('offers the paste path on request, which shows the ordinary form', () => {
      const page = pageFor({ redirect_uri: 'http://evil.com:1/x' });
      expect(page.pasteFallback).toBeFalse();

      page.usePasteInstead();

      expect(page.pasteFallback).toBeTrue();
      expect(page.mode).toBe('refused');
    });
  });

  it('stays the paste flow without a redirect_uri, state or not', () => {
    post.calls.reset();
    const api = apiWith();
    const page = pageWith({ state: 'abc', name: 'laptop' }, api);

    expect(page.mode).toBe('paste');
    expect(page.redirectUri).toBeNull();
    page.onCreated({ token: 'rut-x' } as never);
    expect(page.created?.token).toBe('rut-x');
    expect(post).not.toHaveBeenCalled();
    expect(api.createAccessToken).not.toHaveBeenCalled();
  });
});
