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
import { Component, Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { SplashService } from '../../../shared/components/splash-screen/splasht.service';
import { ProfileService } from '../../pages/profile/service/profile.service';
import { PanelLayoutContentComponent } from './layout-content/panel-layout-content.component';
import { PanelLayoutComponent } from './panel-layout.component';

@Component({ standalone: true, template: '' })
class BlankComponent {}

// The dashboard and the not-found page use the content layout, everything else the routed one: both must behave alike.
[PanelLayoutComponent, PanelLayoutContentComponent].forEach((layout) => {
  describe(`${layout.name} mobile menu`, () => {
    let fixture: ComponentFixture<PanelLayoutComponent | PanelLayoutContentComponent>;
    let router: Router;

    const query = <T extends HTMLElement = HTMLElement>(testId: string): T | null =>
      fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
    const burger = (): HTMLButtonElement => query<HTMLButtonElement>('header-burger')!;
    const click = (testId: string): void => {
      query(testId)!.click();
      fixture.detectChanges();
    };
    const press = (key: string): void => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
      fixture.detectChanges();
    };

    beforeEach(() => {
      TestBed.configureTestingModule({
        imports: [layout],
        providers: [
          provideRouter([
            { path: '', component: BlankComponent },
            { path: 'repositories', component: BlankComponent },
          ]),
          { provide: AuthService, useValue: { isAuthenticated: () => true, username: 'admin' } },
          { provide: ProfileService, useValue: { get: () => of({ role: 'ADMIN' }) } },
          { provide: SplashService, useValue: { setLoading: false } },
        ],
      });
      router = TestBed.inject(Router);
      fixture = TestBed.createComponent(layout as Type<PanelLayoutComponent | PanelLayoutContentComponent>);
      fixture.detectChanges();
    });

    it('renders no mobile sidebar and a collapsed burger by default', () => {
      expect(query('mobile-sidebar')).toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('false');
    });

    it('opens the mobile sidebar from the header burger and reflects it in aria-expanded', () => {
      click('header-burger');

      expect(query('mobile-sidebar')).not.toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('true');
    });

    it('closes on the close button and resets the burger', () => {
      click('header-burger');
      click('mobile-sidebar-close');

      expect(query('mobile-sidebar')).toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('false');
    });

    it('closes on a backdrop click', () => {
      click('header-burger');
      click('mobile-sidebar-backdrop');

      expect(query('mobile-sidebar')).toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('false');
    });

    it('closes on Escape but not on other keys', () => {
      click('header-burger');

      press('Tab');
      press('a');
      expect(query('mobile-sidebar')).not.toBeNull();

      press('Escape');
      expect(query('mobile-sidebar')).toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('false');
    });

    it('closes on a sidebar link click and navigates', async () => {
      click('header-burger');
      click('mobile-sidebar-link-repositories');
      await fixture.whenStable();
      fixture.detectChanges();

      expect(router.url).toBe('/repositories');
      expect(query('mobile-sidebar')).toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('false');
    });

    it('closes on a link to the page it is already on (no navigation happens)', async () => {
      await router.navigateByUrl('/repositories');
      click('header-burger');
      click('mobile-sidebar-link-repositories');

      expect(query('mobile-sidebar')).toBeNull();
    });

    it('closes when the route changes by other means (browser back, a programmatic navigation)', async () => {
      click('header-burger');
      await router.navigateByUrl('/repositories');
      fixture.detectChanges();

      expect(query('mobile-sidebar')).toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('false');
    });

    it('can be reopened after it was closed', () => {
      click('header-burger');
      press('Escape');
      click('header-burger');

      expect(query('mobile-sidebar')).not.toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('true');
    });

    it('ignores Escape while the menu is closed', () => {
      press('Escape');

      expect(query('mobile-sidebar')).toBeNull();
      expect(burger().getAttribute('aria-expanded')).toBe('false');
    });
  });
});

// RPS-1264: the routed page is not held back by a timer.
describe('PanelLayoutComponent routed content', () => {
  it('renders the router outlet at once and leaves the splash screen alone', () => {
    const splash = { setLoading: false };
    TestBed.configureTestingModule({
      imports: [PanelLayoutComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { isAuthenticated: () => true, username: 'admin' } },
        { provide: ProfileService, useValue: { get: () => of({ role: 'ADMIN' }) } },
        { provide: SplashService, useValue: splash },
      ],
    });
    const setLoading = jasmine.createSpy('setLoading');
    Object.defineProperty(splash, 'setLoading', { set: setLoading });

    const fixture = TestBed.createComponent(PanelLayoutComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="panel-content"] router-outlet')).not.toBeNull();
    expect(setLoading).not.toHaveBeenCalled();
  });
});
