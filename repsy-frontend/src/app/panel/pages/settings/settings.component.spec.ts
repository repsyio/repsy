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
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AccessTokensApi } from '../../../../generated/api';
import { SettingsComponent } from './settings.component';

describe('SettingsComponent', () => {
  function render(): HTMLElement {
    const api = jasmine.createSpyObj<AccessTokensApi>('AccessTokensApi', ['listAccessTokens']);
    api.listAccessTokens.and.returnValue(of({ content: [], page: { totalPages: 0 } }) as never);
    TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AccessTokensApi, useValue: api },
      ],
    });
    const fixture = TestBed.createComponent(SettingsComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const byId = (el: HTMLElement, id: string): HTMLElement | null => el.querySelector(`[data-testid="${id}"]`);

  it('shows the title, one navigation entry per section and the access tokens section', () => {
    const el = render();

    expect(byId(el, 'settings-title')?.textContent).toContain('Settings');
    expect(byId(el, 'settings-nav-access-tokens')?.textContent).toContain('Access tokens');
    expect(byId(el, 'access-tokens-section')).not.toBeNull();
  });

  it('keeps the access tokens section id the navigation entry and the password warning link to', () => {
    const el = render();

    expect(el.querySelector('#access-tokens')).not.toBeNull();
  });
});
