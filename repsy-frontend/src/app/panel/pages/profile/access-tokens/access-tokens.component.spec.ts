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
import { of, throwError } from 'rxjs';

import { AccessTokenListItem, AccessTokensApi, AccessTokenScope } from '../../../../../generated/api';
import { DangerModalService } from '../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../shared/components/toast/toast.service';
import { AccessTokensComponent } from './access-tokens.component';

function token(id: string): AccessTokenListItem {
  return {
    id,
    name: id,
    scopes: ['repo:read'] as unknown as Set<AccessTokenScope>,
    expirationDate: '2099-01-01T00:00:00Z',
    createdAt: '2026-01-01T00:00:00Z',
  };
}

function listing(tokens: AccessTokenListItem[], totalPages = 1, totalElements = tokens.length): unknown {
  return { content: tokens, page: { number: 0, size: 5, totalElements, totalPages } };
}

describe('AccessTokensComponent', () => {
  let api: jasmine.SpyObj<AccessTokensApi>;
  let toast: jasmine.SpyObj<ToastService>;
  let danger: DangerModalService;
  let component: AccessTokensComponent;

  beforeEach(() => {
    api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', ['listAccessTokens', 'revokeAccessToken']);
    api.listAccessTokens.and.returnValue(of(listing([token('a'), token('b')], 2, 6)) as never);
    api.revokeAccessToken.and.returnValue(of({}) as never);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    danger = new DangerModalService();
    component = new AccessTokensComponent(api, toast, danger);
  });

  it('loads the first page, five at a time', () => {
    component.ngOnInit();

    expect(api.listAccessTokens).toHaveBeenCalledWith(0, 5);
    expect(component.tokens.map((t) => t.id)).toEqual(['a', 'b']);
    expect(component.pagedData.page.totalPages).toBe(2);
  });

  it('shows the secret once, in the info modal, after a create, and forgets it on close', () => {
    component.onCreated({ token: 'rut-s' } as never);
    expect(component.showInfoModal).toBeTrue();
    expect(component.createdToken.token).toBe('rut-s');

    component.closeInfo(false);

    expect(component.showInfoModal).toBeFalse();
    expect(component.createdToken).toBeUndefined();
  });

  it('revokes after the confirmation and reloads the page that is left', () => {
    component.ngOnInit();
    spyOn(danger, 'show').and.callFake((_t, _b, ok) => ok());

    component.revokeToken(token('a'));

    expect(api.revokeAccessToken).toHaveBeenCalledOnceWith('a');
    expect(toast.show).toHaveBeenCalledWith('Access token revoked successfully', 'success');
    expect(api.listAccessTokens).toHaveBeenCalledWith(0, 5);
    expect(component.operationLock).toBeFalse();
  });

  it('steps back a page when the last token of the last page is revoked', () => {
    component.ngOnInit();
    component.pageNum = 1;
    component.pagedData.page = { number: 1, size: 5, totalElements: 6, totalPages: 2 } as never;
    spyOn(danger, 'show').and.callFake((_t, _b, ok) => ok());
    api.listAccessTokens.calls.reset();

    component.revokeToken(token('f'));

    expect(api.listAccessTokens).toHaveBeenCalledWith(0, 5);
    expect(api.listAccessTokens).not.toHaveBeenCalledWith(1, 5);
  });

  describe('limit and expiry', () => {
    function live(i: number): AccessTokenListItem {
      return { ...token(`l${i}`), expirationDate: '2999-01-01T00:00:00Z' };
    }
    const expired = { ...token('old'), expirationDate: '2000-01-01T00:00:00Z' };

    it('marks an expired token and leaves it in the list', () => {
      expect(component.isExpired(expired)).toBeTrue();
      expect(component.isExpired(live(1))).toBeFalse();
    });

    it('counts only live tokens, tells the parent, and blocks creating at 50', () => {
      const counts: number[] = [];
      component.liveCountChange.subscribe((n) => counts.push(n));
      const fifty = Array.from({ length: 50 }, (_, i) => live(i));
      api.listAccessTokens.and.returnValue(of(listing([...fifty, expired], 1, 51)) as never);

      component.ngOnInit();

      expect(component.liveCount).toBe(50);
      expect(component.limitReached).toBeTrue();
      expect(counts.at(-1)).toBe(50);
    });

    it('is not at the limit with 49 live tokens and any number of expired ones', () => {
      api.listAccessTokens.and.returnValue(
        of(listing([...Array.from({ length: 49 }, (_, i) => live(i)), expired, expired], 1, 51)) as never,
      );

      component.ngOnInit();

      expect(component.limitReached).toBeFalse();
    });
  });

  describe('revoke all', () => {
    beforeEach(() => {
      api.listAccessTokens.and.returnValue(of(listing([token('a'), token('b'), token('c')])) as never);
      spyOn(danger, 'show').and.callFake((_t, _b, ok) => ok());
    });

    it('revokes every token one after the other', () => {
      component.revokeAll();

      expect(api.revokeAccessToken.calls.allArgs().map((a) => a[0])).toEqual(['a', 'b', 'c']);
      expect(component.revokeAllReport).toEqual({ revoked: 3, failed: [] });
      expect(component.operationLock).toBeFalse();
    });

    it('goes on after a failure and reports the names that could not be revoked', () => {
      api.revokeAccessToken.and.callFake(((id: string) =>
        id === 'b' ? throwError(() => new Error('boom')) : of({})) as never);

      component.revokeAll();

      expect(api.revokeAccessToken).toHaveBeenCalledTimes(3);
      expect(component.revokeAllReport).toEqual({ revoked: 2, failed: ['b'] });
      expect(toast.show).toHaveBeenCalledWith('2 revoked, 1 could not be revoked', 'error');
    });

    it('revokes nothing when the list cannot be read', () => {
      api.listAccessTokens.and.returnValue(throwError(() => new Error('boom')) as never);

      component.revokeAll();

      expect(api.revokeAccessToken).not.toHaveBeenCalled();
    });
  });

  it('says never for a token that was not used', () => {
    expect(component.timeAgo(undefined)).toBe('Never');
  });
});
