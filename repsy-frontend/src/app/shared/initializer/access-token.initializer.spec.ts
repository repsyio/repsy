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

import { DOCUMENT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { accessTokenInitializer } from './access-token.initializer';

describe('accessTokenInitializer', () => {
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    localStorage.removeItem('token');
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
  });

  afterEach(() => localStorage.removeItem('token'));

  function initialize(location: { hash: string } | undefined): void {
    const document = { defaultView: location ? { location } : null } as unknown as Document;
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: router },
        { provide: DOCUMENT, useValue: document },
      ],
    });

    TestBed.runInInjectionContext(() => accessTokenInitializer())();
  }

  it('stores the access token from the URL fragment and goes to the root', () => {
    initialize({ hash: '#access_token=abc.def-123&token_type=bearer' });

    expect(localStorage.getItem('token')).toBe('abc.def-123');
    expect(router.navigate).toHaveBeenCalledOnceWith(['/']);
  });

  it('finds the token wherever it sits in the fragment', () => {
    initialize({ hash: '#state=xyz&access_token=tok' });

    expect(localStorage.getItem('token')).toBe('tok');
  });

  it('does nothing when the fragment has no access token', () => {
    initialize({ hash: '#state=xyz' });

    expect(localStorage.getItem('token')).toBeNull();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('does nothing when the fragment is empty', () => {
    initialize({ hash: '' });

    expect(localStorage.getItem('token')).toBeNull();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('does nothing when the document has no window', () => {
    initialize(undefined);

    expect(localStorage.getItem('token')).toBeNull();
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
