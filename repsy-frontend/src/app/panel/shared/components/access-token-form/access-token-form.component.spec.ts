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
import { FormBuilder } from '@angular/forms';
import { of, throwError } from 'rxjs';

import { AccessTokenCreated, AccessTokensApi, AccessTokenScope, ProfileInfo } from '../../../../../generated/api';
import { ProfileService } from '../../../pages/profile/services/profile.service';
import { ToastService } from '../toast/toast.service';
import { AccessTokenFormComponent } from './access-token-form.component';

describe('AccessTokenFormComponent', () => {
  let api: jasmine.SpyObj<AccessTokensApi>;
  let toast: jasmine.SpyObj<ToastService>;
  let profile: jasmine.SpyObj<ProfileService>;
  let component: AccessTokenFormComponent;
  const createdToken = {
    id: 'i',
    name: 'n',
    scopes: ['repo:read'],
    token: 'rut-secret',
  } as unknown as AccessTokenCreated;

  beforeEach(() => {
    api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', ['createAccessToken']);
    api.createAccessToken.and.returnValue(of(createdToken) as never);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    profile = jasmine.createSpyObj<ProfileService>('ProfileService', ['get']);
    profile.get.and.returnValue(of({ role: 'USER' } as ProfileInfo));
    component = new AccessTokenFormComponent(api, new FormBuilder(), toast, profile);
  });

  function init(name: string, scopes: AccessTokenScope[]): void {
    component.initialName = name;
    component.initialScopes = scopes;
    component.ngOnInit();
  }

  it('starts with the pre-filled name and scopes and the longest expiry', () => {
    init('Repsy CLI', ['repo:read', 'repo:write']);

    expect(component.form.value.name).toBe('Repsy CLI');
    expect([...component.selectedScopes]).toEqual(['repo:read', 'repo:write']);
    expect(component.form.value.expirationDate).toBe(component.maxDate);
    expect(component.canSubmit).toBeTrue();
  });

  it('cuts a name longer than 80 characters', () => {
    init('x'.repeat(200), ['repo:read']);

    expect(component.form.value.name.length).toBe(80);
  });

  it('does not submit without a scope, nor without a name', () => {
    init('cli', []);
    component.submit();
    expect(component.canSubmit).toBeFalse();
    expect(api.createAccessToken).not.toHaveBeenCalled();

    component.toggleScope('scan:read', true);
    component.form.patchValue({ name: '  ' });
    expect(component.form.value.name.trim()).toBe('');
  });

  it('is blocked, with the reason, while the limit is reached', () => {
    init('cli', ['repo:read']);
    component.blockedReason = 'limit';

    expect(component.canSubmit).toBeFalse();
    component.submit();
    expect(api.createAccessToken).not.toHaveBeenCalled();
  });

  it('has no way to ask for a token without an expiry', () => {
    init('cli', ['repo:read']);
    component.form.patchValue({ expirationDate: null });

    expect(component.canSubmit).toBeFalse();
  });

  it('allows the last day of the window and refuses the day after', () => {
    init('cli', ['repo:read']);
    component.form.patchValue({ expirationDate: component.maxDate });
    expect(component.canSubmit).toBeTrue();

    const dayAfter = new Date(Date.parse(component.maxDate + 'T00:00:00Z') + 86_400_000).toISOString().slice(0, 10);
    component.form.patchValue({ expirationDate: dayAfter });
    expect(component.canSubmit).toBeFalse();
  });

  it('refuses a date outside tomorrow..one year', () => {
    init('cli', ['repo:read']);
    component.form.patchValue({ expirationDate: '2000-01-01' });

    expect(component.expirationInvalid).toBeTrue();
    expect(component.canSubmit).toBeFalse();
  });

  it('creates the token with the selected scopes only, in the offered order, and emits it once', () => {
    init('cli', []);
    component.toggleScope('repo:write', true);
    component.toggleScope('repo:read', true);
    component.toggleScope('repo:manage', true);
    component.toggleScope('repo:manage', false);
    const emitted: AccessTokenCreated[] = [];
    component.created.subscribe((t) => emitted.push(t));

    component.submit();

    const payload = api.createAccessToken.calls.mostRecent().args[0];
    expect(payload.name).toBe('cli');
    // An array on purpose: HttpClient would serialise a Set as {}.
    expect(Array.isArray(payload.scopes)).toBeTrue();
    expect(payload.scopes as unknown).toEqual(['repo:read', 'repo:write']);
    expect(payload.expirationDate).toBeDefined();
    expect(emitted).toEqual([createdToken]);
    expect(toast.show).toHaveBeenCalledOnceWith('Access token created successfully.', 'success');
    expect(component.loading).toBeFalse();
  });

  describe('the scopes on offer by role', () => {
    const offered = (): string[] => component.scopeOptions.map((s) => s.scope);

    it('offers repo:manage to an administrator', () => {
      profile.get.and.returnValue(of({ role: 'ADMIN' } as ProfileInfo));
      init('cli', ['repo:read']);

      expect(offered()).toEqual(['repo:read', 'repo:write', 'repo:manage', 'scan:read']);
    });

    it('does not offer repo:manage to a user who is not an administrator', () => {
      init('cli', ['repo:read']);

      expect(offered()).toEqual(['repo:read', 'repo:write', 'scan:read']);
    });

    it('does not offer repo:manage while the profile is unknown or when it fails', () => {
      profile.get.and.returnValue(throwError(() => new Error('boom')));
      init('cli', ['repo:read']);

      expect(offered()).toEqual(['repo:read', 'repo:write', 'scan:read']);
    });

    it('drops a repo:manage that a link asked for when the user is not an administrator', () => {
      init('cli', ['repo:read', 'repo:manage']);

      expect([...component.selectedScopes]).toEqual(['repo:read']);
    });

    it('keeps a repo:manage that a link asked for when the user is an administrator', () => {
      profile.get.and.returnValue(of({ role: 'ADMIN' } as ProfileInfo));
      init('cli', ['repo:read', 'repo:manage']);

      expect([...component.selectedScopes].sort()).toEqual(['repo:manage', 'repo:read']);
    });

    it('sends scan:read alone when that is all that was picked', () => {
      init('ci', []);
      component.toggleScope('scan:read', true);

      component.submit();

      expect(api.createAccessToken.calls.mostRecent().args[0].scopes as unknown).toEqual(['scan:read']);
    });

    it('never sends repo:manage for a user who is not an administrator', () => {
      init('cli', ['repo:read']);
      component.toggleScope('repo:manage', true);

      component.submit();

      expect(api.createAccessToken.calls.mostRecent().args[0].scopes as unknown).toEqual(['repo:read']);
    });
  });
});
