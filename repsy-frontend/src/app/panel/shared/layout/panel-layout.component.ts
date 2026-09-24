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

import { CommonModule, DOCUMENT } from '@angular/common';
import { ChangeDetectorRef, Component, inject, NgZone, OnDestroy, OnInit, ViewEncapsulation } from '@angular/core';
import { RouterModule, RouterOutlet } from '@angular/router';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { FooterComponent } from '../../../shared/components/footer/footer.component';
import { PanelHeaderComponent } from '../../../shared/components/panel-header/panel-header.component';
import { SidebarComponent } from '../components/sidebar/sidebar.component';

/** Tailwind's `md` breakpoint: from here on the desktop sidebar is shown and the mobile menu cannot be. */
const DESKTOP_QUERY = '(min-width: 48rem)';

/**
 * The one layout of the panel: header, sidebar (only with a session), content and footer. Routed pages
 * render into its `<router-outlet>`; a page that is not a route of its own (the dashboard, which
 * `AuthRedirectComponent` renders at "/") projects its content instead. It owns the mobile menu: the
 * header asks to open it, the sidebar asks to close it (close button, backdrop, link, Escape,
 * navigation) and the layout closes it when the viewport reaches the desktop width. While it is open
 * the page behind does not scroll.
 */
@Component({
  selector: 'app-panel-layout',
  imports: [CommonModule, SidebarComponent, FooterComponent, PanelHeaderComponent, RouterModule, RouterOutlet],
  encapsulation: ViewEncapsulation.None,
  templateUrl: './panel-layout.component.html',
  standalone: true,
})
export class PanelLayoutComponent implements OnInit, OnDestroy {
  public isMobileMenuOpen = false;
  public isAuthenticated = false;

  private readonly authService = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly zone = inject(NgZone);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly desktopQuery = this.document.defaultView?.matchMedia?.(DESKTOP_QUERY);
  private readonly onViewportChange = (event: MediaQueryListEvent): void => {
    if (event.matches) {
      // Unlike a click, a media query listener neither runs in the Angular zone (zone.js does not patch
      // it) nor marks anything dirty. The dashboard lives in a view container of the OnPush
      // AuthRedirectComponent, which would otherwise never refresh this view.
      this.zone.run(() => {
        this.closeMobileMenu();
        this.changeDetector.markForCheck();
      });
    }
  };
  private scrollLocked = false;

  public ngOnInit(): void {
    // RPS-1264: nothing is loading here (isAuthenticated() is synchronous), so the outlet renders at
    // once instead of sitting behind a fixed 500 ms timer; each routed page shows its own loading state.
    this.isAuthenticated = this.authService.isAuthenticated();
    this.desktopQuery?.addEventListener('change', this.onViewportChange);
  }

  public ngOnDestroy(): void {
    this.desktopQuery?.removeEventListener('change', this.onViewportChange);
    this.unlockScroll();
  }

  /** Without a session there is no sidebar, so there is nothing to open. */
  public setMobileMenuOpen(open: boolean): void {
    if (open && !this.isAuthenticated) {
      return;
    }
    this.isMobileMenuOpen = open;
    if (open) {
      this.lockScroll();
    } else {
      this.unlockScroll();
    }
  }

  public closeMobileMenu(): void {
    this.setMobileMenuOpen(false);
  }

  // The same body style the splash screen uses (SplashService), so the two never fight over it.
  private lockScroll(): void {
    this.scrollLocked = true;
    this.document.body.style.overflow = 'hidden';
  }

  private unlockScroll(): void {
    if (this.scrollLocked) {
      this.scrollLocked = false;
      this.document.body.style.overflow = '';
    }
  }
}
