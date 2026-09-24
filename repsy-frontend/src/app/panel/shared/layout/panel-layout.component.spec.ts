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
import { ChangeDetectionStrategy, Component, NgZone } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { SplashService } from '../../../shared/components/splash-screen/splasht.service';
import { ProfileService } from '../../pages/profile/service/profile.service';
import { PanelLayoutComponent } from './panel-layout.component';

@Component({ standalone: true, template: '' })
class BlankComponent {}

/**
 * What the dashboard does: it is not a route of the layout, it projects its content into it, and it is
 * rendered by an OnPush component (AuthRedirectComponent), so nothing refreshes it unless it is marked dirty.
 */
@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PanelLayoutComponent],
  template: '<app-panel-layout><p data-testid="projected">Dashboard</p></app-panel-layout>',
})
class ProjectingHostComponent {}

/** A `MediaQueryList` the test can flip: `setDesktop(true)` is the viewport reaching the `md` width. */
class FakeMediaQueryList extends EventTarget {
  public matches = false;
  public readonly media = '(min-width: 48rem)';

  public setDesktop(matches: boolean): void {
    this.matches = matches;
    this.dispatchEvent(Object.assign(new Event('change'), { matches }));
  }
}

const provideSession = (
  authenticated: boolean,
  profile = { get: jasmine.createSpy('get').and.returnValue(of({ role: 'ADMIN' })) },
) => [
  {
    provide: AuthService,
    useValue: { isAuthenticated: () => authenticated, username: authenticated ? 'admin' : null },
  },
  { provide: ProfileService, useValue: profile },
  { provide: SplashService, useValue: { setLoading: false } },
];

// The routed pages and the dashboard (projected content) share one layout: the mobile menu must behave alike in both.
[
  { name: 'routed', host: PanelLayoutComponent },
  { name: 'projected', host: ProjectingHostComponent },
].forEach(({ name, host }) => {
  describe(`PanelLayoutComponent mobile menu (${name})`, () => {
    let fixture: ComponentFixture<unknown>;
    let router: Router;
    let viewport: FakeMediaQueryList;

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
      viewport = new FakeMediaQueryList();
      spyOn(window, 'matchMedia').and.returnValue(viewport as unknown as MediaQueryList);
      TestBed.configureTestingModule({
        imports: [host],
        providers: [
          provideRouter([
            { path: '', component: BlankComponent },
            { path: 'repositories', component: BlankComponent },
          ]),
          ...provideSession(true),
        ],
      });
      router = TestBed.inject(Router);
      fixture = TestBed.createComponent(host);
      fixture.detectChanges();
    });

    afterEach(() => {
      fixture.destroy();
      document.body.style.overflow = '';
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

    describe('when the viewport reaches the desktop width', () => {
      it('closes the open menu, and it does not come back when the viewport narrows again', () => {
        click('header-burger');
        expect(query('mobile-sidebar')).not.toBeNull();

        viewport.setDesktop(true);
        fixture.detectChanges();
        expect(query('mobile-sidebar')).toBeNull();
        expect(burger().getAttribute('aria-expanded')).toBe('false');

        viewport.setDesktop(false);
        fixture.detectChanges();
        expect(query('mobile-sidebar')).toBeNull();
        expect(burger().getAttribute('aria-expanded')).toBe('false');
      });

      it('reacts inside the Angular zone, since a media query listener is not patched by zone.js', () => {
        const run = spyOn(TestBed.inject(NgZone), 'run').and.callThrough();
        click('header-burger');
        run.calls.reset();

        viewport.setDesktop(true);

        expect(run).toHaveBeenCalled();
      });

      it('releases the scroll lock with it', () => {
        click('header-burger');
        viewport.setDesktop(true);

        expect(document.body.style.overflow).toBe('');
      });

      it('narrowing the viewport alone opens nothing', () => {
        viewport.setDesktop(true);
        viewport.setDesktop(false);
        fixture.detectChanges();

        expect(query('mobile-sidebar')).toBeNull();
      });

      it('stops listening once the layout is destroyed', () => {
        const listeners = spyOn(viewport, 'removeEventListener').and.callThrough();

        fixture.destroy();

        expect(listeners).toHaveBeenCalledOnceWith('change', jasmine.any(Function));
      });
    });

    describe('page scroll', () => {
      it('is locked while the menu is open and restored on every way of closing it', () => {
        expect(document.body.style.overflow).toBe('');

        click('header-burger');
        expect(document.body.style.overflow).toBe('hidden');
        click('mobile-sidebar-close');
        expect(document.body.style.overflow).toBe('');

        click('header-burger');
        click('mobile-sidebar-backdrop');
        expect(document.body.style.overflow).toBe('');

        click('header-burger');
        press('Escape');
        expect(document.body.style.overflow).toBe('');

        click('header-burger');
        click('mobile-sidebar-link-repositories');
        expect(document.body.style.overflow).toBe('');
      });

      it('is restored when the layout is destroyed while the menu is open', () => {
        click('header-burger');
        expect(document.body.style.overflow).toBe('hidden');

        fixture.destroy();

        expect(document.body.style.overflow).toBe('');
      });

      it('is left alone when the menu was never opened', () => {
        document.body.style.overflow = 'scroll';

        fixture.destroy();

        expect(document.body.style.overflow).toBe('scroll');
      });
    });
  });
});

describe('PanelLayoutComponent content', () => {
  const create = <T>(component: new () => T, authenticated = true): ComponentFixture<T> => {
    TestBed.configureTestingModule({
      imports: [component],
      providers: [provideRouter([]), ...provideSession(authenticated)],
    });
    const fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    return fixture;
  };
  const query = (fixture: ComponentFixture<unknown>, testId: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);

  // RPS-1264: the routed page is not held back by a timer.
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

    expect(query(fixture, 'panel-content')!.querySelector('router-outlet')).not.toBeNull();
    expect(setLoading).not.toHaveBeenCalled();
  });

  it('projects the content of a page that is not a route (the dashboard)', () => {
    const fixture = create(ProjectingHostComponent);

    expect(query(fixture, 'panel-content')!.querySelector('[data-testid="projected"]')).not.toBeNull();
  });

  it('shows the header, the sidebar and the burger with a session', () => {
    const fixture = create(PanelLayoutComponent);

    expect(query(fixture, 'header')).not.toBeNull();
    expect(query(fixture, 'sidebar')).not.toBeNull();
    expect(query(fixture, 'header-burger')).not.toBeNull();
    expect(query(fixture, 'panel-content')!.className).toContain('max-w-[1025px]');
  });

  describe('without a session (the not-found page for an anonymous visitor)', () => {
    let profile: { get: jasmine.Spy };
    let fixture: ComponentFixture<PanelLayoutComponent>;

    beforeEach(() => {
      profile = { get: jasmine.createSpy('get').and.returnValue(of({ role: 'ADMIN' })) };
      TestBed.configureTestingModule({
        imports: [PanelLayoutComponent],
        providers: [provideRouter([]), ...provideSession(false, profile)],
      });
      fixture = TestBed.createComponent(PanelLayoutComponent);
      fixture.detectChanges();
    });

    afterEach(() => {
      document.body.style.overflow = '';
    });

    it('renders no sidebar and requests no profile', () => {
      expect(query(fixture, 'sidebar')).toBeNull();
      expect(query(fixture, 'mobile-sidebar')).toBeNull();
      expect(profile.get).not.toHaveBeenCalled();
    });

    it('has no burger, since there is no menu for it to open', () => {
      expect(query(fixture, 'header')).not.toBeNull();
      expect(query(fixture, 'header-burger')).toBeNull();
      expect(query(fixture, 'panel-content')!.className).toContain('max-w-[1400px]');
    });

    it('cannot be told to open a menu that does not exist', () => {
      fixture.componentInstance.setMobileMenuOpen(true);
      fixture.detectChanges();

      expect(fixture.componentInstance.isMobileMenuOpen).toBeFalse();
      expect(document.body.style.overflow).toBe('');
    });
  });
});
