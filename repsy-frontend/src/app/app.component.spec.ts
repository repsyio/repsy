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

import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Subject } from 'rxjs';

import { AppComponent } from './app.component';
import { AuthService } from './auth/pages/services/auth.service';

describe('AppComponent', () => {
  let sessionEndedElsewhere$: Subject<void>;
  let router: { url: string; navigateByUrl: jasmine.Spy };

  beforeEach(() => {
    sessionEndedElsewhere$ = new Subject<void>();
    router = { url: '/repositories', navigateByUrl: jasmine.createSpy('navigateByUrl') };
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        { provide: AuthService, useValue: { sessionEndedElsewhere$ } },
        { provide: Router, useValue: router },
      ],
    }).overrideComponent(AppComponent, { set: { imports: [], template: '' } });
  });

  // RPS-1621
  it('sends the tab to the login form, remembering its page, when another tab ended the session', () => {
    TestBed.createComponent(AppComponent);

    sessionEndedElsewhere$.next();

    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/login?returnUrl=%2Frepositories');
  });

  it('does nothing while the session stands, and stops listening once destroyed', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(router.navigateByUrl).not.toHaveBeenCalled();

    fixture.destroy();
    sessionEndedElsewhere$.next();

    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
