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
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { PanelHeaderComponent } from './panel-header.component';

describe('PanelHeaderComponent burger', () => {
  let fixture: ComponentFixture<PanelHeaderComponent>;
  let emitted: boolean[];

  const burger = (): HTMLButtonElement => fixture.nativeElement.querySelector('[data-testid="header-burger"]');

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PanelHeaderComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: { username: 'admin' } }],
    });
    fixture = TestBed.createComponent(PanelHeaderComponent);
    emitted = [];
    fixture.componentInstance.mobileMenuToggle.subscribe((open: boolean) => emitted.push(open));
    fixture.detectChanges();
  });

  it('reports the state it is given, not one of its own', () => {
    expect(burger().getAttribute('aria-expanded')).toBe('false');

    fixture.componentRef.setInput('isMobileMenuOpen', true);
    fixture.detectChanges();
    expect(burger().getAttribute('aria-expanded')).toBe('true');

    fixture.componentRef.setInput('isMobileMenuOpen', false);
    fixture.detectChanges();
    expect(burger().getAttribute('aria-expanded')).toBe('false');
  });

  it('asks for the opposite of the current state and leaves aria-expanded to its parent', () => {
    burger().click();
    fixture.detectChanges();
    expect(emitted).toEqual([true]);
    expect(burger().getAttribute('aria-expanded')).toBe('false');

    fixture.componentRef.setInput('isMobileMenuOpen', true);
    burger().click();
    expect(emitted).toEqual([true, false]);
  });
});

// RPS-1264: the Profile entry is a router link, not a document navigation that boots the SPA again.
describe('PanelHeaderComponent profile link', () => {
  let fixture: ComponentFixture<PanelHeaderComponent>;
  let router: Router;

  const query = (testId: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PanelHeaderComponent],
      providers: [
        provideRouter([{ path: 'profile', component: PanelHeaderComponent }]),
        { provide: AuthService, useValue: { username: 'admin' } },
      ],
    });
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(PanelHeaderComponent);
    fixture.detectChanges();
    query('header-avatar')!.click();
    fixture.detectChanges();
  });

  it('points at the absolute /profile route, whatever page the header is on', () => {
    expect(query('header-menu-profile')!.getAttribute('href')).toBe('/profile');
  });

  it('navigates through the router without a document load, and closes the menu', async () => {
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });

    query('header-menu-profile')!.dispatchEvent(click);
    await fixture.whenStable();
    fixture.detectChanges();

    // RouterLink cancels the browser's own navigation, which is what would reload the page.
    expect(click.defaultPrevented).toBeTrue();
    expect(router.url).toBe('/profile');
    expect(query('header-menu')).toBeNull();
  });
});
