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

import { NgOptimizedImage, ViewportScroller } from '@angular/common';
import { Component, ElementRef, EventEmitter, HostListener, Input, Output, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { ProfileAvatarComponent } from '../../../panel/shared/components/avatar/profile.avatar.component';
import { DividerComponent } from '../divider/divider.component';

@Component({
  selector: 'app-panel-header',
  templateUrl: './panel-header.component.html',
  styleUrls: ['./panel-header.component.css'],
  imports: [RouterLink, DividerComponent, NgOptimizedImage, ProfileAvatarComponent],
  standalone: true,
})
export class PanelHeaderComponent {
  public username: string;
  // Owned by the layout: the burger only asks for a state, it never keeps one of its own.
  @Input() public isMobileMenuOpen = false;
  // The burger opens the sidebar, so it only exists where there is one (not without a session).
  @Input() public hasMobileMenu = true;

  @Output() mobileMenuToggle = new EventEmitter<boolean>();

  /** The avatar button: only present while there is a session. */
  private readonly profileToggle = viewChild<ElementRef<HTMLElement>>('profileToggle');

  constructor(
    private readonly authService: AuthService,
    private readonly router: Router,
    viewportScroller: ViewportScroller,
  ) {
    this.username = this.authService.username;

    viewportScroller.setOffset(() => [0, document.querySelector('header')?.getBoundingClientRect().height ?? 0]);
  }

  /** Read on every check, so the header follows a login or a logout without being told. */
  public get isAuthenticated(): boolean {
    return this.authService.isAuthenticated();
  }

  docDropdown = false;
  profileDropdown = false;

  toggleMobileMenu() {
    this.mobileMenuToggle.emit(!this.isMobileMenuOpen);
  }

  toggleProfileDropdown() {
    this.profileDropdown = !this.profileDropdown;
    this.docDropdown = false;
  }

  public logOut(): void {
    this.authService.logOut();
    this.router.navigateByUrl('login');
  }

  /**
   * Closes the menus on a click anywhere else. The click that opens the profile menu is left alone here rather
   * than stopped from bubbling (RPS-1347): it has to reach the other dropdowns' own outside-click handlers, or a
   * row menu would stay open beside it.
   */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event) {
    if (this.profileToggle()?.nativeElement.contains(event.target as Node)) {
      return;
    }
    this.docDropdown = false;
    this.profileDropdown = false;
  }

  @HostListener('window:scroll')
  onWindowScroll() {
    if (window.pageYOffset > 100) {
      document.querySelector('header')?.classList.add('smaller');
    } else {
      document.querySelector('header')?.classList.remove('smaller');
    }
  }
}
