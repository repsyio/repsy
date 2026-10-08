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
import { of } from 'rxjs';

import { AccessTokensApi, AccessTokenScope } from '../../../../generated/api';
import { CliAuthComponent } from './cli-auth.component';

function liveToken(i: number): unknown {
  return {
    id: `t${i}`,
    name: `t${i}`,
    scopes: [],
    expirationDate: '2999-01-01T00:00:00Z',
    createdAt: '2026-01-01T00:00:00Z',
  };
}

function pageFor(query: Record<string, string>, tokens: unknown[] = []): CliAuthComponent {
  const route = { snapshot: { queryParamMap: convertToParamMap(query) } } as unknown as ActivatedRoute;
  const api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', ['listAccessTokens']);
  api.listAccessTokens.and.returnValue(of({ content: tokens, page: {} }) as never);
  const component = new CliAuthComponent(route, api);
  component.ngOnInit();
  return component;
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

  it('shows the secret once the token is created', () => {
    const page = pageFor({});
    expect(page.created).toBeNull();

    page.onCreated({ token: 'rut-x' } as never);

    expect(page.created?.token).toBe('rut-x');
  });
});
