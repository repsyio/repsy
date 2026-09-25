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
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { DropdownComponent } from '../../../panel/shared/components/dropdown/dropdown.component';
import { PanelHeaderComponent } from './panel-header.component';

describe('PanelHeaderComponent burger', () => {
  let fixture: ComponentFixture<PanelHeaderComponent>;
  let emitted: boolean[];

  const burger = (): HTMLButtonElement => fixture.nativeElement.querySelector('[data-testid="header-burger"]');

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PanelHeaderComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { username: 'admin', isAuthenticated: () => true } },
      ],
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

  it('has no burger where there is no sidebar to open', () => {
    fixture.componentRef.setInput('hasMobileMenu', false);
    fixture.detectChanges();

    expect(burger()).toBeNull();
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
        { provide: AuthService, useValue: { username: 'admin', isAuthenticated: () => true } },
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

// RPS-1294, RPS-1306: the header also renders on the not-found page of an anonymous visitor, who has no username
// and no session: it shows the logo and a Log in link, not the avatar menu.
describe('PanelHeaderComponent without a session', () => {
  let fixture: ComponentFixture<PanelHeaderComponent>;

  const query = (testId: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PanelHeaderComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { username: null, isAuthenticated: () => false } },
      ],
    });
    fixture = TestBed.createComponent(PanelHeaderComponent);
  });

  it('renders without failing on the missing username', () => {
    expect(() => fixture.detectChanges()).not.toThrow();
  });

  it('shows the logo, the docs link and a Log in link to the login page at /', () => {
    fixture.detectChanges();

    expect(query('header-logo')).not.toBeNull();
    expect(query('header-docs')).not.toBeNull();
    expect(query('header-login')!.textContent).toContain('Log in');
    expect(query('header-login')!.getAttribute('href')).toBe('/');
  });

  it('shows no avatar, no profile menu and no way to log out', () => {
    fixture.detectChanges();

    expect(query('header-avatar')).toBeNull();
    expect(query('header-menu')).toBeNull();
    expect(query('header-menu-profile')).toBeNull();
    expect(query('header-menu-logout')).toBeNull();
  });
});

describe('PanelHeaderComponent with a session', () => {
  it('shows the avatar and no Log in link', () => {
    TestBed.configureTestingModule({
      imports: [PanelHeaderComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { username: 'admin', isAuthenticated: () => true } },
      ],
    });
    const fixture = TestBed.createComponent(PanelHeaderComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="header-avatar"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="header-login"]')).toBeNull();
  });
});

// RPS-1347: the click that opens the profile menu used to be stopped at the button, so it never reached the row
// dropdowns' outside-click handlers and a row menu stayed open beside it.
@Component({
  selector: 'app-header-with-row-menu',
  imports: [PanelHeaderComponent, DropdownComponent],
  template: `
    <app-panel-header />
    <p data-testid="elsewhere">Page content</p>
    <app-dropdown>
      <button type="button" data-testid="row-action">Delete</button>
    </app-dropdown>
  `,
})
class HeaderWithRowMenuComponent {}

describe('PanelHeaderComponent next to a row menu', () => {
  let fixture: ComponentFixture<HeaderWithRowMenuComponent>;

  const query = (testId: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);

  const click = (testId: string): void => {
    query(testId)!.click();
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HeaderWithRowMenuComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { username: 'admin', isAuthenticated: () => true } },
      ],
    });
    fixture = TestBed.createComponent(HeaderWithRowMenuComponent);
    // The menus listen on the document, so the clicks have to bubble up to a real one.
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => fixture.nativeElement.remove());

  it('opens the profile menu and keeps it open (its own click is not an outside click)', () => {
    click('header-avatar');

    expect(query('header-menu')).not.toBeNull();
  });

  it('closes an open row menu when the profile menu is opened', () => {
    click('dropdown-toggle');
    expect(query('dropdown-menu')).not.toBeNull();

    click('header-avatar');

    expect(query('header-menu')).not.toBeNull();
    expect(query('dropdown-menu')).toBeNull();
  });

  it('closes the profile menu when a row menu is opened', () => {
    click('header-avatar');

    click('dropdown-toggle');

    expect(query('header-menu')).toBeNull();
    expect(query('dropdown-menu')).not.toBeNull();
  });

  it('closes the profile menu on a click anywhere else, and with the avatar again', () => {
    click('header-avatar');
    click('elsewhere');
    expect(query('header-menu')).toBeNull();

    click('header-avatar');
    click('header-avatar');
    expect(query('header-menu')).toBeNull();
  });
});
