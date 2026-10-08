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
import { of } from 'rxjs';

import { AccessTokenCreated, AccessTokensApi, AccessTokenScope } from '../../../../../generated/api';
import { ToastService } from '../toast/toast.service';
import { AccessTokenFormComponent } from './access-token-form.component';

describe('AccessTokenFormComponent', () => {
  let api: jasmine.SpyObj<AccessTokensApi>;
  let toast: jasmine.SpyObj<ToastService>;
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
    component = new AccessTokenFormComponent(api, new FormBuilder(), toast);
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
});
